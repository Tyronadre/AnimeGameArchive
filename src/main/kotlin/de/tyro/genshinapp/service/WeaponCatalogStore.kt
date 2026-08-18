package de.tyro.genshinapp.service

import de.tyro.genshinapp.configuration.LegacyGameWeaponSchemaCleanup
import de.tyro.genshinapp.entity.GameWeapon
import de.tyro.genshinapp.entity.GameWeaponImage
import de.tyro.genshinapp.entity.GameWeaponMaterialCost
import de.tyro.genshinapp.entity.GameWeaponProgression
import de.tyro.genshinapp.entity.Material
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.MaterialCost
import de.tyro.genshinapp.model.WeaponImageType
import de.tyro.genshinapp.repository.GameWeaponRepository
import de.tyro.genshinapp.repository.MaterialRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface WeaponCatalogStore {
    fun getWeapons(): List<WeaponDefinition>

    fun findWeapon(key: String): WeaponDefinition?

    fun saveWeapon(weapon: WeaponDefinition): WeaponDefinition
}

@Service
class JpaWeaponCatalogStore(
    private val repository: GameWeaponRepository,
    private val materialRepository: MaterialRepository,
    legacySchemaCleanup: LegacyGameWeaponSchemaCleanup,
) : WeaponCatalogStore {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        legacySchemaCleanup.prepareForCatalogAccess()
    }

    @Transactional(readOnly = true)
    override fun getWeapons(): List<WeaponDefinition> =
        repository.findAllByOrderByNameAsc().mapNotNull(::toDefinition)

    @Transactional(readOnly = true)
    override fun findWeapon(key: String): WeaponDefinition? =
        repository.findByKey(GoodKeyNormalizer.normalize(key))?.let(::toDefinition)

    @Transactional
    override fun saveWeapon(weapon: WeaponDefinition): WeaponDefinition {
        val normalizedKey = GoodKeyNormalizer.normalize(weapon.key)
        require(normalizedKey.isNotBlank()) { "Weapon key must not be blank" }
        val entity = repository.findByKey(normalizedKey)
            ?: GameWeapon().also { it.key = normalizedKey }

        entity.name = weapon.name
        entity.rarity = weapon.rarity
        entity.weaponType = weapon.weaponType
        entity.secondaryStatType = weapon.secondaryStatType
        entity.baseAttack = weapon.baseAttack
        entity.baseSecondaryStat = weapon.baseSecondaryStat
        entity.description = weapon.description
        entity.region = weapon.region
        entity.obtainMethod = weapon.obtainMethod
        entity.releaseVersion = weapon.releaseVersion
        entity.passiveName = weapon.passiveName
        entity.passiveDescription = weapon.passiveDescription
        entity.story = weapon.story
        entity.hoyolabEntryId = weapon.hoyolabEntryId
        entity.hoyolabPageVersion = weapon.hoyolabPageVersion
        entity.hoyolabDataVersion = weapon.hoyolabDataVersion

        synchronizeImages(entity, weapon)
        synchronizeMaterialCosts(entity, weapon.ascensionCosts)
        synchronizeProgressions(entity, weapon.hoyolabAscension)

        return toDefinition(repository.save(entity)) ?: weapon.copy(key = normalizedKey)
    }

    private fun synchronizeImages(entity: GameWeapon, weapon: WeaponDefinition) {
        val existingByType = entity.images.associateBy(GameWeaponImage::imageType)
        entity.images.removeIf { it.imageType !in WeaponImageType.entries }
        WeaponImageType.entries.forEach { type ->
            val image = existingByType[type] ?: GameWeaponImage().also {
                it.weapon = entity
                it.imageType = type
                entity.images += it
            }
            image.localUrl = weapon.imageUrl(type)
            image.remoteUrl = weapon.remoteImageUrl(type)
        }
    }

    private fun synchronizeMaterialCosts(
        entity: GameWeapon,
        costsByPhase: Map<Int, List<MaterialCost>>,
    ) {
        val desired = costsByPhase.toSortedMap().flatMap { (phase, costs) ->
            costs.mapIndexed { order, cost -> DesiredMaterialCost(phase, order, cost) }
        }
        val materialsByGameId = findOrCreateMaterials(desired.map(DesiredMaterialCost::cost))
        val desiredByIdentity = desired.associateBy(DesiredMaterialCost::identity)
        entity.materialCosts.removeIf { it.identity() !in desiredByIdentity }
        val existingByIdentity = entity.materialCosts.associateBy { it.identity() }

        desired.forEach { desiredCost ->
            val stored = existingByIdentity[desiredCost.identity()]
                ?: GameWeaponMaterialCost().also {
                    it.weapon = entity
                    it.phase = desiredCost.phase
                    it.material = materialsByGameId.getValue(desiredCost.cost.id)
                    entity.materialCosts += it
                }
            stored.amount = desiredCost.cost.count
            stored.materialOrder = desiredCost.order
        }
    }

    private fun findOrCreateMaterials(costs: Collection<MaterialCost>): Map<Int, Material> {
        val costsById = costs.filter { it.id >= 0 }.associateBy(MaterialCost::id)
        if (costsById.isEmpty()) return emptyMap()
        val existingById = materialRepository
            .findAllByGameIdInOrderByNameAsc(costsById.keys)
            .associateBy(Material::gameId)
        val materials = costsById.map { (gameId, cost) ->
            (existingById[gameId] ?: Material().also { it.gameId = gameId }).also {
                it.name = cost.name
            }
        }
        return materialRepository.saveAll(materials).associateBy(Material::gameId)
    }

    private fun synchronizeProgressions(
        entity: GameWeapon,
        progressions: List<WeaponHoyolabAscension>,
    ) {
        val desiredLevels = progressions.mapTo(linkedSetOf(), WeaponHoyolabAscension::level)
        entity.progressions.removeIf { it.level !in desiredLevels }
        val existingByLevel = entity.progressions.associateBy(GameWeaponProgression::level)

        progressions.forEach { desired ->
            val stored = existingByLevel[desired.level] ?: GameWeaponProgression().also {
                it.weapon = entity
                it.level = desired.level
                entity.progressions += it
            }
            stored.attackBeforeAscension = desired.attackBeforeAscension
            stored.attackAfterAscension = desired.attackAfterAscension
            stored.secondaryStat = desired.secondaryStat
        }
    }

    private fun toDefinition(entity: GameWeapon): WeaponDefinition? = runCatching {
        WeaponDefinition(
            key = entity.key,
            name = entity.name,
            rarity = entity.rarity,
            weaponType = entity.weaponType,
            secondaryStatType = entity.secondaryStatType,
            baseAttack = entity.baseAttack,
            baseSecondaryStat = entity.baseSecondaryStat,
            description = entity.description,
            region = entity.region,
            obtainMethod = entity.obtainMethod,
            releaseVersion = entity.releaseVersion,
            passiveName = entity.passiveName,
            passiveDescription = entity.passiveDescription,
            story = entity.story,
            imageUrls = entity.images.mapNotNull { image ->
                image.localUrl?.let { image.imageType to it }
            }.toMap(),
            remoteImageUrls = entity.images.mapNotNull { image ->
                image.remoteUrl?.let { image.imageType to it }
            }.toMap(),
            hoyolabEntryId = entity.hoyolabEntryId,
            hoyolabPageVersion = entity.hoyolabPageVersion,
            hoyolabDataVersion = entity.hoyolabDataVersion ?: 0,
            hoyolabAscension = entity.progressions.map { progression ->
                WeaponHoyolabAscension(
                    level = progression.level,
                    attackBeforeAscension = progression.attackBeforeAscension,
                    attackAfterAscension = progression.attackAfterAscension,
                    secondaryStat = progression.secondaryStat,
                )
            },
            ascensionCosts = entity.materialCosts
                .groupBy(GameWeaponMaterialCost::phase)
                .toSortedMap()
                .mapValues { (_, costs) ->
                    costs.sortedBy(GameWeaponMaterialCost::materialOrder).map { cost ->
                        MaterialCost(
                            id = cost.material.gameId,
                            name = cost.material.name,
                            count = cost.amount,
                        )
                    }
                },
        )
    }.onFailure {
        logger.warn("Stored weapon data for '{}' is invalid", entity.key, it)
    }.getOrNull()

    private fun GameWeaponMaterialCost.identity(): MaterialCostIdentity =
        MaterialCostIdentity(phase, material.gameId)

    private data class DesiredMaterialCost(
        val phase: Int,
        val order: Int,
        val cost: MaterialCost,
    ) {
        fun identity(): MaterialCostIdentity = MaterialCostIdentity(phase, cost.id)
    }

    private data class MaterialCostIdentity(
        val phase: Int,
        val materialId: Int,
    )
}
