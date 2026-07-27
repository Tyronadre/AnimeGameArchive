package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.InventoryMaterialBalance
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialDefinition
import de.tyro.genshinapp.model.MaterialSourceDefinition
import de.tyro.genshinapp.model.MaterialSourceRole
import de.tyro.genshinapp.model.MaterialSourceType
import de.tyro.genshinapp.model.PlayerMaterialPlan
import de.tyro.genshinapp.model.PlayerSnapshot
import org.springframework.stereotype.Service

@Service
class MissingMaterialsPageService(
    private val materialCatalogService: MaterialCatalogService,
    private val craftingService: MaterialCraftingService,
    private val domainScheduleService: DomainScheduleService,
) {
    fun create(plan: PlayerMaterialPlan): MissingMaterialsPage {
        val sources = materialCatalogService.getSources(MaterialSourceType.entries)
        val gemMaterials = materialCatalogService.getMaterialsByCategories(
            setOf(MaterialCategory.GEM),
        )
        val materials = (sources.flatMap { source ->
            source.materials.map { it.material }
        } + gemMaterials).associateBy(MaterialDefinition::id)
        val planned = plan.aggregateMaterials.associateBy(InventoryMaterialBalance::id)
        val itemCache = mutableMapOf<Int, MissingMaterialItem>()

        fun item(id: Int): MissingMaterialItem? {
            itemCache[id]?.let { return it }
            val result = materials[id]
                ?.let { material -> materialItem(material, planned[id], plan.snapshot) }
                ?: planned[id]?.let(::materialItem)
                ?: return null
            itemCache[id] = result
            return result
        }

        val talentDomains = sources
            .filter { it.type == MaterialSourceType.TALENT_DOMAIN }
            .mapNotNull domain@{ source ->
                val families = source.materials
                    .filter { it.role == MaterialSourceRole.DROP }
                    .groupBy { it.familyOrder }
                    .toSortedMap()
                    .values
                    .mapNotNull family@{ memberships ->
                        val familyMaterials = memberships.sortedBy { it.materialOrder }
                            .mapNotNull { item(it.material.id) }
                        if (familyMaterials.isEmpty()) return@family null
                        val schedule = memberships.firstNotNullOfOrNull { it.schedule }
                        TalentBookFamily(
                            name = familyMaterials.first().name.substringAfter(" of "),
                            farmableToday = schedule?.let(domainScheduleService::isFarmable)
                                ?: false,
                            daysMessageKey = schedule?.messageKey.orEmpty(),
                            materials = familyMaterials,
                        )
                    }
                if (families.isEmpty()) return@domain null
                TalentDomain(source.name, source.region.orEmpty(), families)
            }

        fun boss(source: MaterialSourceDefinition): BossMaterialGroup {
            val drops = source.materials
                .filter { it.role == MaterialSourceRole.DROP }
                .sortedBy { it.materialOrder }
                .mapNotNull { item(it.material.id) }
            val gems = source.materials
                .filter { it.role == MaterialSourceRole.GEM }
                .groupBy { it.familyOrder }
                .toSortedMap()
                .values
                .mapNotNull { memberships ->
                    val familyMaterials = memberships.sortedBy { it.materialOrder }
                        .mapNotNull { item(it.material.id) }
                    if (familyMaterials.isEmpty()) return@mapNotNull null
                    GemMaterialFamily(
                        name = familyMaterials.first().name.removeSuffix(" Sliver"),
                        materials = familyMaterials,
                    )
                }
            return BossMaterialGroup(source.name, drops, gems)
        }

        val worldBosses = sources.filter { it.type == MaterialSourceType.WORLD_BOSS }.map(::boss)
        val weeklyBosses = sources.filter { it.type == MaterialSourceType.WEEKLY_BOSS }.map(::boss)
        val enemyDrops = sources.filter { it.type == MaterialSourceType.ENEMY }
            .mapNotNull { source ->
                val familyMaterials = source.materials.sortedBy { it.materialOrder }
                    .mapNotNull { item(it.material.id) }
                if (familyMaterials.isEmpty()) {
                    null
                } else {
                    EnemyMaterialFamily(source.name, familyMaterials)
                }
            }

        val missingItems = plan.missingMaterials.map(::materialItem)
        val collectables = missingItems.filter { it.category == MaterialCategory.COLLECTABLE }
        val gemFamilies = gemMaterials
            .filter { it.craftingFamily != null }
            .groupBy(MaterialDefinition::craftingFamily)
            .values
            .map { family ->
                val familyMaterials = family.sortedBy { it.craftingTier }.mapNotNull { item(it.id) }
                GemMaterialFamily(
                    name = familyMaterials.firstOrNull()?.name?.substringBeforeLast(" ").orEmpty(),
                    materials = familyMaterials,
                )
            }
            .sortedBy(GemMaterialFamily::name)
        val separatelyGrouped = setOf(
            MaterialCategory.TALENT_BOOK,
            MaterialCategory.WORLD_BOSS,
            MaterialCategory.WEEKLY_BOSS,
            MaterialCategory.ENEMY_DROP,
            MaterialCategory.COLLECTABLE,
            MaterialCategory.GEM,
        )
        val otherMaterials = missingItems.filter { it.category !in separatelyGrouped }

        return MissingMaterialsPage(
            talentDomains = talentDomains,
            worldBosses = worldBosses,
            weeklyBosses = weeklyBosses,
            enemyDrops = enemyDrops,
            collectables = collectables,
            gemFamilies = gemFamilies,
            otherMaterials = otherMaterials,
            alphabeticalMaterials = missingItems.sortedBy(MissingMaterialItem::name),
            allItemsById = itemCache + missingItems.associateBy(MissingMaterialItem::id),
        )
    }

    private fun materialItem(
        material: MaterialDefinition,
        planned: InventoryMaterialBalance?,
        snapshot: PlayerSnapshot,
    ): MissingMaterialItem {
        if (planned != null) return materialItem(planned)
        val availability = craftingService.inventoryAvailability(material.id, snapshot.inventory)
        return MissingMaterialItem(
            id = material.id,
            name = material.name,
            required = 0,
            owned = availability.owned,
            craftable = availability.craftable,
            needed = 0,
            imageUrl = materialCatalogService.materialImageUrl(material.id),
            category = material.category,
        )
    }

    private fun materialItem(balance: InventoryMaterialBalance): MissingMaterialItem =
        MissingMaterialItem(
            id = balance.id,
            name = balance.name,
            required = balance.required,
            owned = balance.owned,
            craftable = balance.craftable,
            needed = balance.missing,
            imageUrl = balance.imageUrl,
            category = balance.category,
        )
}

data class MissingMaterialsPage(
    val talentDomains: List<TalentDomain>,
    val worldBosses: List<BossMaterialGroup>,
    val weeklyBosses: List<BossMaterialGroup>,
    val enemyDrops: List<EnemyMaterialFamily>,
    val collectables: List<MissingMaterialItem>,
    val gemFamilies: List<GemMaterialFamily>,
    val otherMaterials: List<MissingMaterialItem>,
    val alphabeticalMaterials: List<MissingMaterialItem>,
    val allItemsById: Map<Int, MissingMaterialItem>,
)

data class MissingMaterialItem(
    val id: Int,
    val name: String,
    val required: Long,
    val owned: Long,
    val craftable: Long,
    val needed: Long,
    val imageUrl: String?,
    val category: MaterialCategory,
) {
    val available: Long
        get() = owned + craftable

    val freeToUse: Long
        get() = (owned - required).coerceAtLeast(0L)
}

data class TalentDomain(
    val name: String,
    val region: String,
    val families: List<TalentBookFamily>,
)

data class TalentBookFamily(
    val name: String,
    val farmableToday: Boolean,
    val daysMessageKey: String,
    val materials: List<MissingMaterialItem>,
)

data class BossMaterialGroup(
    val name: String,
    val drops: List<MissingMaterialItem>,
    val gemFamilies: List<GemMaterialFamily>,
)

data class GemMaterialFamily(
    val name: String,
    val materials: List<MissingMaterialItem>,
) {
    val topTier: MissingMaterialItem?
        get() = materials.lastOrNull()
}

data class EnemyMaterialFamily(
    val enemyName: String,
    val materials: List<MissingMaterialItem>,
)
