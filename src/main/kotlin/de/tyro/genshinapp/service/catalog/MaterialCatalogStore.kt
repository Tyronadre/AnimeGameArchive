package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.entity.Material
import de.tyro.genshinapp.entity.HoyowikiEntry
import jakarta.persistence.EntityManager
import org.springframework.context.annotation.DependsOn
import de.tyro.genshinapp.entity.MaterialSource
import de.tyro.genshinapp.entity.MaterialSourceMaterial
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialDefinition
import de.tyro.genshinapp.model.MaterialSchedule
import de.tyro.genshinapp.model.MaterialSourceDefinition
import de.tyro.genshinapp.model.MaterialSourceMaterialDefinition
import de.tyro.genshinapp.model.MaterialSourceRole
import de.tyro.genshinapp.model.MaterialSourceType
import de.tyro.genshinapp.repository.MaterialRepository
import de.tyro.genshinapp.repository.MaterialSourceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface MaterialCatalogStore {
    fun getMaterials(): List<MaterialDefinition>

    fun getMaterialsByIds(ids: Collection<Int>): List<MaterialDefinition>

    fun getMaterialsByCategories(categories: Collection<MaterialCategory>): List<MaterialDefinition>

    fun findMaterial(id: Int): MaterialDefinition?

    fun saveMaterials(materials: Collection<MaterialDefinition>)

    fun ensureSources(sources: Collection<MaterialSourceSeed>)

    fun getSources(types: Collection<MaterialSourceType>): List<MaterialSourceDefinition>
}

@Service
@DependsOn("materialIdentityRepair")
class JpaMaterialCatalogStore(
    private val materialRepository: MaterialRepository,
    private val sourceRepository: MaterialSourceRepository,
    private val entityManager: EntityManager,
) : MaterialCatalogStore {
    @Transactional(readOnly = true)
    override fun getMaterials(): List<MaterialDefinition> =
        materialRepository.findAllByOrderByNameAsc().map(::toDefinition)

    @Transactional(readOnly = true)
    override fun getMaterialsByIds(ids: Collection<Int>): List<MaterialDefinition> =
        if (ids.isEmpty()) emptyList()
        else materialRepository.findAllByGameIdInOrderByNameAsc(ids).map(::toDefinition)

    @Transactional(readOnly = true)
    override fun getMaterialsByCategories(
        categories: Collection<MaterialCategory>,
    ): List<MaterialDefinition> =
        if (categories.isEmpty()) emptyList()
        else materialRepository.findAllByTypeInOrderByNameAsc(categories.map { it.name })
            .map(::toDefinition)

    @Transactional(readOnly = true)
    override fun findMaterial(id: Int): MaterialDefinition? =
        materialRepository.findByGameId(id)?.let(::toDefinition)

    @Transactional
    override fun saveMaterials(materials: Collection<MaterialDefinition>) {
        val desired = materials.distinctBy(MaterialDefinition::id)
        if (desired.isEmpty()) return
        val existingByGameId = materialRepository
            .findAllByGameIdInOrderByNameAsc(desired.map(MaterialDefinition::id))
            .associateBy(Material::gameId)
        val pageIds = desired.mapNotNull(MaterialDefinition::hoyolabEntryId).distinct()
        val pages = if (pageIds.isEmpty()) emptyMap() else entityManager.createQuery(
            "select e from HoyowikiEntry e where e.pageId in :ids", HoyowikiEntry::class.java,
        ).setParameter("ids", pageIds).resultList.associateBy { it.pageId }
        materialRepository.saveAll(
            desired.map { material ->
                (existingByGameId[material.id] ?: Material().also { it.gameId = material.id })
                    .apply(material, pages)
            },
        )
    }

    @Transactional
    override fun ensureSources(sources: Collection<MaterialSourceSeed>) {
        val materialIds = sources.flatMap { source -> source.materials.map { it.materialId } }.toSet()
        val materialsByGameId = materialRepository.findAllByGameIdInOrderByNameAsc(materialIds)
            .associateBy(Material::gameId)

        sources.forEach { seed ->
            val existing = sourceRepository.findByCatalogKey(seed.key)
            if (existing?.origin?.startsWith("HOYOWIKI") == true) return@forEach
            val source = existing ?: MaterialSource().also {
                it.catalogKey = seed.key
                it.name = seed.name
                it.sourceType = seed.type.name
                it.region = seed.region
                it.displayOrder = seed.displayOrder
                it.origin = "BUNDLED"
            }
            val existingMemberships = source.materials.mapTo(mutableSetOf()) {
                it.material.gameId to it.sourceRole
            }
            seed.materials.forEach materialLoop@{ membership ->
                val material = materialsByGameId[membership.materialId] ?: return@materialLoop
                val identity = material.gameId to membership.role.name
                if (!existingMemberships.add(identity)) return@materialLoop
                source.materials += MaterialSourceMaterial().also {
                    it.source = source
                    it.material = material
                    it.sourceRole = membership.role.name
                    it.familyOrder = membership.familyOrder
                    it.materialOrder = membership.materialOrder
                    it.scheduleKey = membership.schedule?.name
                }
            }
            sourceRepository.save(source)
        }
    }

    @Transactional(readOnly = true)
    override fun getSources(types: Collection<MaterialSourceType>): List<MaterialSourceDefinition> {
        if (types.isEmpty()) return emptyList()
        return sourceRepository.findAllBySourceTypeInOrderByDisplayOrderAscNameAsc(
            types.map { it.name },
        ).map(::toDefinition)
    }

    private fun Material.apply(material: MaterialDefinition, pages: Map<Long, HoyowikiEntry>): Material {
        name = material.name
        if (categoryOrigin != "HOYOWIKI" || material.categoryOrigin == "HOYOWIKI") {
            type = material.category.name
            categoryOrigin = material.categoryOrigin
            craftingFamily = material.craftingFamily
            craftingTier = material.craftingTier
            conversionGroup = material.conversionGroup
        }
        material.hoyolabEntryId?.let { pages[it] }?.let { hoyowikiEntry = it }
        return this
    }

    private fun toDefinition(entity: Material): MaterialDefinition =
        MaterialDefinition(
            id = entity.gameId,
            name = entity.name,
            category = runCatching { MaterialCategory.valueOf(entity.type.orEmpty()) }
                .getOrDefault(MaterialCategory.OTHER),
            craftingFamily = entity.craftingFamily,
            craftingTier = entity.craftingTier,
            conversionGroup = entity.conversionGroup,
            hoyolabEntryId = entity.hoyowikiEntry?.pageId,
            categoryOrigin = entity.categoryOrigin,
        )

    private fun toDefinition(entity: MaterialSource): MaterialSourceDefinition =
        MaterialSourceDefinition(
            key = entity.catalogKey,
            name = entity.name,
            type = MaterialSourceType.valueOf(entity.sourceType),
            region = entity.region,
            displayOrder = entity.displayOrder,
            origin = entity.origin ?: "BUNDLED",
            hoyolabEntryId = entity.hoyowikiEntry?.pageId,
            materials = entity.materials
                .sortedWith(
                    compareBy(
                        MaterialSourceMaterial::familyOrder,
                        MaterialSourceMaterial::materialOrder,
                    ),
                )
                .map { membership ->
                    MaterialSourceMaterialDefinition(
                        material = toDefinition(membership.material),
                        role = MaterialSourceRole.valueOf(membership.sourceRole),
                        familyOrder = membership.familyOrder,
                        materialOrder = membership.materialOrder,
                        schedule = membership.scheduleKey?.let(MaterialSchedule::valueOf),
                    )
                },
        )
}
