package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.InventoryMaterialBalance
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialCraftingInfo
import de.tyro.genshinapp.model.MaterialDefinition
import de.tyro.genshinapp.model.MaterialInventoryAvailability
import org.springframework.stereotype.Service

@Service
class MaterialCraftingService(
    private val catalogService: CharacterCatalogService,
) {
    private val craftingInfoById: Map<Int, MaterialCraftingInfo> by lazy {
        buildCraftingCatalog()
    }
    private val craftingFamilies: Map<String, List<MaterialCraftingInfo>> by lazy {
        craftingInfoById.values
            .filter { it.familyKey != null }
            .groupBy { requireNotNull(it.familyKey) }
    }

    fun infoFor(materialId: Int): MaterialCraftingInfo? = craftingInfoById[materialId]

    fun inventoryAvailability(
        materialId: Int,
        inventory: Map<String, Long>,
    ): MaterialInventoryAvailability {
        val info = craftingInfoById[materialId]
            ?: return MaterialInventoryAvailability(owned = 0, craftable = 0)
        val owned = quantity(info, inventory)
        val family = info.familyKey?.let(craftingFamilies::get).orEmpty()

        val craftable = when (info.category) {
            MaterialCategory.GEM,
            MaterialCategory.TALENT_BOOK,
            MaterialCategory.WEAPON_ASCENSION,
            MaterialCategory.ENEMY_DROP,
            -> tieredCraftableAmount(info, family, inventory)

            MaterialCategory.WEEKLY_BOSS -> {
                val convertibleDrops = family
                    .filter { it.material.id != materialId }
                    .fold(0L) { total, sibling ->
                        saturatingAdd(total, quantity(sibling, inventory))
                    }
                minOf(convertibleDrops, inventory.getOrDefault(DREAM_SOLVENT_KEY, 0L))
            }

            else -> 0L
        }

        return MaterialInventoryAvailability(owned = owned, craftable = craftable)
    }

    fun applyCrafting(
        balances: List<InventoryMaterialBalance>,
        inventory: Map<String, Long>,
    ): List<InventoryMaterialBalance> {
        val inferredWeaponInfo = balances.mapNotNull(::inferredWeaponCraftingInfo)
        val effectiveInfoById = craftingInfoById + inferredWeaponInfo.associateBy {
            it.material.id
        }
        val effectiveFamilies = effectiveInfoById.values
            .filter { it.familyKey != null }
            .groupBy { requireNotNull(it.familyKey) }
        val result = balances.associate { balance ->
            val category = effectiveInfoById[balance.id]?.category ?: MaterialCategory.OTHER
            balance.id to balance.copy(category = category)
        }.toMutableMap()

        effectiveFamilies.values
            .filter { family ->
                family.firstOrNull()?.category in TIERED_CATEGORIES &&
                    family.any { result.containsKey(it.material.id) }
            }
            .forEach { family -> applyTieredFamily(family, inventory, result) }

        applyWeeklyBossFamilies(inventory, result)

        return balances.map { result.getValue(it.id) }
    }

    private fun inferredWeaponCraftingInfo(
        balance: InventoryMaterialBalance,
    ): MaterialCraftingInfo? {
        if (balance.id !in WEAPON_ASCENSION_ID_RANGE) return null
        val offset = balance.id - WEAPON_ASCENSION_ID_RANGE.first
        return MaterialCraftingInfo(
            material = MaterialDefinition(balance.id, balance.name),
            category = MaterialCategory.WEAPON_ASCENSION,
            familyKey = "weapon:${offset / WEAPON_ASCENSION_FAMILY_SIZE}",
            tier = offset % WEAPON_ASCENSION_FAMILY_SIZE,
        )
    }

    private fun applyTieredFamily(
        family: List<MaterialCraftingInfo>,
        inventory: Map<String, Long>,
        result: MutableMap<Int, InventoryMaterialBalance>,
    ) {
        var craftedFromLowerTier = 0L
        family.sortedBy { it.tier }.forEach { info ->
            val balance = result[info.material.id]
            val owned = quantity(info, inventory)
            val available = saturatingAdd(owned, craftedFromLowerTier)
            val required = balance?.required ?: 0L

            if (balance != null) {
                result[info.material.id] = balance.copy(
                    owned = owned,
                    craftable = craftedFromLowerTier,
                    missing = (required - available).coerceAtLeast(0L),
                )
            }

            val unused = (available - required).coerceAtLeast(0L)
            craftedFromLowerTier = unused / TIERED_RECIPE_COST
        }
    }

    private fun applyWeeklyBossFamilies(
        inventory: Map<String, Long>,
        result: MutableMap<Int, InventoryMaterialBalance>,
    ) {
        var remainingDreamSolvent = inventory.getOrDefault(DREAM_SOLVENT_KEY, 0L)
        craftingFamilies.values
            .filter { family ->
                family.firstOrNull()?.category == MaterialCategory.WEEKLY_BOSS &&
                    family.any { result.containsKey(it.material.id) }
            }
            .sortedBy { family -> family.minOf { it.material.id } }
            .forEach { family ->
                var convertibleSurplus = family.fold(0L) { total, info ->
                    val required = result[info.material.id]?.required ?: 0L
                    val surplus = (quantity(info, inventory) - required).coerceAtLeast(0L)
                    saturatingAdd(total, surplus)
                }

                family.sortedBy { it.material.id }.forEach materialLoop@{ info ->
                    val balance = result[info.material.id] ?: return@materialLoop
                    val owned = quantity(info, inventory)
                    val deficit = (balance.required - owned).coerceAtLeast(0L)
                    val craftable = minOf(deficit, convertibleSurplus, remainingDreamSolvent)
                    result[info.material.id] = balance.copy(
                        owned = owned,
                        craftable = craftable,
                        missing = deficit - craftable,
                    )
                    convertibleSurplus -= craftable
                    remainingDreamSolvent -= craftable
                }
            }
    }

    private fun tieredCraftableAmount(
        target: MaterialCraftingInfo,
        family: List<MaterialCraftingInfo>,
        inventory: Map<String, Long>,
    ): Long {
        var craftedFromLowerTier = 0L
        family.sortedBy { it.tier }.forEach { info ->
            if (info.material.id == target.material.id) return craftedFromLowerTier
            craftedFromLowerTier = saturatingAdd(
                quantity(info, inventory),
                craftedFromLowerTier,
            ) / TIERED_RECIPE_COST
        }
        return 0L
    }

    private fun quantity(
        info: MaterialCraftingInfo,
        inventory: Map<String, Long>,
    ): Long = inventory.getOrDefault(
        GoodKeyNormalizer.normalize(info.material.name),
        0L,
    )

    private fun buildCraftingCatalog(): Map<Int, MaterialCraftingInfo> {
        val characters = catalogService.getCharacters()
        val usages = collectMaterialUsages(characters)
        val categories = usages.mapValues { (_, usage) -> categoryFor(usage) }
        val enemyFamilies = enemyFamilies(characters, categories)
        val weeklyFamilies = weeklyFamilies(usages, categories)

        val preliminary = usages.values.associate { usage ->
            val category = categories.getValue(usage.id)
            val familyKey = when (category) {
                MaterialCategory.GEM -> "gem:${gemFamilyName(usage.name)}"
                MaterialCategory.TALENT_BOOK -> "talent:${talentBookFamilyName(usage.name)}"
                MaterialCategory.ENEMY_DROP -> enemyFamilies[usage.id]
                MaterialCategory.WEEKLY_BOSS -> weeklyFamilies[usage.id]
                else -> null
            }
            usage.id to MaterialCraftingInfo(
                material = MaterialDefinition(usage.id, usage.name),
                category = category,
                familyKey = familyKey,
            )
        }

        val tierByMaterialId = preliminary.values
            .filter { it.category in TIERED_CATEGORIES && it.familyKey != null }
            .groupBy { requireNotNull(it.familyKey) }
            .flatMap { (_, family) ->
                family.sortedBy { it.material.id }
                    .mapIndexed { tier, info -> info.material.id to tier }
            }
            .toMap()

        return preliminary.mapValues { (id, info) ->
            info.copy(tier = tierByMaterialId[id])
        }
    }

    private fun collectMaterialUsages(
        characters: List<CharacterDefinition>,
    ): Map<Int, MaterialUsage> {
        val usages = linkedMapOf<Int, MaterialUsage>()
        characters.forEach { character ->
            character.ascensionCosts.values.flatten().forEach { material ->
                usages.getOrPut(material.id) {
                    MaterialUsage(material.id, material.name)
                }.usedForAscension = true
            }
            character.talentCosts.values.flatten().forEach { material ->
                usages.getOrPut(material.id) {
                    MaterialUsage(material.id, material.name)
                }.usedForTalents = true
            }
        }
        return usages
    }

    private fun categoryFor(usage: MaterialUsage): MaterialCategory = when {
        GEM_SUFFIXES.any { usage.name.endsWith(it, ignoreCase = true) } ->
            MaterialCategory.GEM
        TALENT_BOOK_PREFIXES.any { usage.name.startsWith(it, ignoreCase = true) } ->
            MaterialCategory.TALENT_BOOK
        usage.id in ENEMY_DROP_ID_RANGE && usage.usedForAscension && usage.usedForTalents ->
            MaterialCategory.ENEMY_DROP
        usage.id in BOSS_DROP_ID_RANGE && usage.usedForTalents && !usage.usedForAscension ->
            MaterialCategory.WEEKLY_BOSS
        usage.id in BOSS_DROP_ID_RANGE && usage.usedForAscension ->
            MaterialCategory.WORLD_BOSS
        usage.id in COLLECTABLE_ID_RANGE && usage.usedForAscension ->
            MaterialCategory.COLLECTABLE
        else -> MaterialCategory.OTHER
    }

    private fun enemyFamilies(
        characters: List<CharacterDefinition>,
        categories: Map<Int, MaterialCategory>,
    ): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        characters.forEach { character ->
            val ids = (
                character.ascensionCosts.values.flatten() +
                    character.talentCosts.values.flatten()
                )
                .map { it.id }
                .filter { categories[it] == MaterialCategory.ENEMY_DROP }
                .distinct()
                .sorted()
            if (ids.isNotEmpty()) {
                val familyKey = "enemy:${ids.first()}"
                ids.forEach { result[it] = familyKey }
            }
        }
        return result
    }

    private fun weeklyFamilies(
        usages: Map<Int, MaterialUsage>,
        categories: Map<Int, MaterialCategory>,
    ): Map<Int, String> =
        usages.values
            .filter { categories[it.id] == MaterialCategory.WEEKLY_BOSS }
            .sortedBy { it.id }
            .chunked(WEEKLY_BOSS_FAMILY_SIZE)
            .filter { it.size == WEEKLY_BOSS_FAMILY_SIZE }
            .flatMap { family ->
                val familyKey = "weekly:${family.first().id}"
                family.map { it.id to familyKey }
            }
            .toMap()

    private fun gemFamilyName(name: String): String {
        val suffix = GEM_SUFFIXES.first { name.endsWith(it, ignoreCase = true) }
        return GoodKeyNormalizer.normalize(name.dropLast(suffix.length))
    }

    private fun talentBookFamilyName(name: String): String {
        val prefix = TALENT_BOOK_PREFIXES.first {
            name.startsWith(it, ignoreCase = true)
        }
        return GoodKeyNormalizer.normalize(name.drop(prefix.length))
    }

    private fun saturatingAdd(first: Long, second: Long): Long =
        if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second

    private data class MaterialUsage(
        val id: Int,
        val name: String,
        var usedForAscension: Boolean = false,
        var usedForTalents: Boolean = false,
    )

    companion object {
        private const val TIERED_RECIPE_COST = 3L
        private const val WEEKLY_BOSS_FAMILY_SIZE = 3
        private const val DREAM_SOLVENT_KEY = "dreamsolvent"
        private val ENEMY_DROP_ID_RANGE = 112000..112999
        private val BOSS_DROP_ID_RANGE = 113000..113999
        private val COLLECTABLE_ID_RANGE = 101000..101999
        private val WEAPON_ASCENSION_ID_RANGE = 114001..114999
        private const val WEAPON_ASCENSION_FAMILY_SIZE = 4
        private val GEM_SUFFIXES = listOf(" Sliver", " Fragment", " Chunk", " Gemstone")
        private val TALENT_BOOK_PREFIXES = listOf(
            "Teachings of ",
            "Guide to ",
            "Philosophies of ",
        )
        private val TIERED_CATEGORIES = setOf(
            MaterialCategory.GEM,
            MaterialCategory.TALENT_BOOK,
            MaterialCategory.WEAPON_ASCENSION,
            MaterialCategory.ENEMY_DROP,
        )
    }
}
