package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.InventoryMaterialBalance
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialCraftingInfo
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
            MaterialCategory.TALENT_BOOK,
            MaterialCategory.WEAPON_ASCENSION,
            MaterialCategory.ENEMY_DROP,
            -> tieredCraftableAmount(info, family, inventory)

            MaterialCategory.GEM -> {
                val upgraded = tieredCraftableAmount(info, family, inventory)
                val dustCost = GEM_CONVERSION_DUST.getOrElse(info.tier ?: -1) { Long.MAX_VALUE }
                val convertible = craftingInfoById.values
                    .filter { sibling ->
                        sibling.category == MaterialCategory.GEM &&
                            sibling.tier == info.tier && sibling.material.id != materialId
                    }
                    .sumOf { quantity(it, inventory) }
                saturatingAdd(
                    upgraded,
                    minOf(convertible, inventory.getOrDefault(DUST_OF_AZOTH_KEY, 0L) / dustCost),
                )
            }

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
        val effectiveInfoById = craftingInfoById
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
        applyGemConversions(inventory, result)

        return balances.map { result.getValue(it.id) }
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

    private fun applyGemConversions(
        inventory: Map<String, Long>,
        result: MutableMap<Int, InventoryMaterialBalance>,
    ) {
        var remainingDust = inventory.getOrDefault(DUST_OF_AZOTH_KEY, 0L)
        craftingInfoById.values.filter { it.category == MaterialCategory.GEM }
            .groupBy { it.tier }
            .toSortedMap(compareBy(nullsFirst()) { it })
            .forEach tierLoop@{ (tier, gems) ->
                val dustCost = GEM_CONVERSION_DUST.getOrElse(tier ?: return@tierLoop) { return@tierLoop }
                var surplus = gems.sumOf { gem ->
                    val required = result[gem.material.id]?.required ?: 0L
                    (quantity(gem, inventory) - required).coerceAtLeast(0L)
                }
                gems.sortedBy { it.material.id }.forEach gemLoop@{ gem ->
                    val balance = result[gem.material.id] ?: return@gemLoop
                    val deficit = balance.missing
                    val converted = minOf(deficit, surplus, remainingDust / dustCost)
                    if (converted > 0) {
                        result[gem.material.id] = balance.copy(
                            craftable = saturatingAdd(balance.craftable, converted),
                            missing = deficit - converted,
                        )
                        surplus -= converted
                        remainingDust -= converted * dustCost
                    }
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
        return catalogService.getMaterials().associate { material ->
            material.id to MaterialCraftingInfo(
                material = material,
                category = material.category,
                familyKey = material.craftingFamily,
                tier = material.craftingTier,
                conversionGroup = material.conversionGroup,
            )
        }
    }

    private fun saturatingAdd(first: Long, second: Long): Long =
        if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second

    companion object {
        private const val TIERED_RECIPE_COST = 3L
        private const val DREAM_SOLVENT_KEY = "dreamsolvent"
        private const val DUST_OF_AZOTH_KEY = "dustofazoth"
        private val GEM_CONVERSION_DUST = longArrayOf(1L, 3L, 9L, 27L)
        private val TIERED_CATEGORIES = setOf(
            MaterialCategory.GEM,
            MaterialCategory.TALENT_BOOK,
            MaterialCategory.WEAPON_ASCENSION,
            MaterialCategory.ENEMY_DROP,
        )
    }
}
