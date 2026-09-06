package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.entity.*
import de.tyro.genshinapp.model.*
import de.tyro.genshinapp.repository.GameCharacterRepository
import de.tyro.genshinapp.repository.MaterialRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface CharacterCatalogStore {
    fun getCharacters(): List<CharacterDefinition>

    fun findCharacter(key: String): CharacterDefinition?

    fun saveCharacter(character: CharacterDefinition): CharacterDefinition
}

@Service
class JpaCharacterCatalogStore(
    private val characterRepository: GameCharacterRepository,
    private val materialRepository: MaterialRepository,
) : CharacterCatalogStore {
    private val logger = LoggerFactory.getLogger(javaClass)


    @Transactional(readOnly = true)
    override fun getCharacters(): List<CharacterDefinition> =
        characterRepository.findAllByOrderByNameAsc().mapNotNull(::toDefinition)

    @Transactional(readOnly = true)
    override fun findCharacter(key: String): CharacterDefinition? =
        characterRepository.findByKey(key.trim().lowercase())?.let(::toDefinition)

    @Transactional
    override fun saveCharacter(character: CharacterDefinition): CharacterDefinition {
        val normalizedKey = character.key.trim().lowercase()
        val entity = characterRepository.findByKey(normalizedKey)
            ?: GameCharacter().also { it.key = normalizedKey }

        entity.gameId = character.id
        entity.name = character.name
        entity.title = character.title
        entity.description = character.description
        entity.weapon = character.weapon
        entity.rarity = character.rarity
        entity.birthday = character.birthday
        entity.element = character.element
        entity.affiliation = character.affiliation
        entity.region = character.region
        entity.constellation = character.constellation
        entity.ascensionStatType = character.ascensionStatType
        entity.imageResourceKey = character.imageResourceKey
        entity.talentResourceKey = character.talentResourceKey
        entity.hoyolabEntryId = character.hoyolabEntryId
        entity.hoyolabPageVersion = character.hoyolabPageVersion
        entity.hoyolabDataVersion = character.hoyolabDataVersion

        synchronizeImages(entity, character)
        synchronizeMaterialCosts(entity, character)
        synchronizeTalents(entity, character.talents)
        synchronizeStats(entity, character.stats)

        return toDefinition(characterRepository.save(entity)) ?: character
    }

    private fun synchronizeImages(
        entity: GameCharacter,
        character: CharacterDefinition,
    ) {
        val desiredTypes = CharacterImageType.entries.filterTo(linkedSetOf()) { type ->
            character.imageUrls[type] != null || character.remoteImageUrls[type] != null
        }
        entity.images.removeIf { it.imageType !in desiredTypes }
        val existingByType = entity.images.associateBy(GameCharacterImage::imageType)
        desiredTypes.forEach { type ->
            val image = existingByType[type] ?: GameCharacterImage().also {
                it.character = entity
                it.imageType = type
                entity.images += it
            }
            image.localUrl = character.imageUrls[type]
            image.remoteUrl = character.remoteImageUrls[type]
        }
    }

    private fun synchronizeMaterialCosts(
        entity: GameCharacter,
        character: CharacterDefinition,
    ) {
        val desired = buildList {
            character.ascensionCosts.toSortedMap().forEach { (level, costs) ->
                costs.forEachIndexed { order, cost ->
                    add(DesiredMaterialCost(GameCharacterCostType.ASCENSION, level, order, cost))
                }
            }
            character.talentCosts.toSortedMap().forEach { (level, costs) ->
                costs.forEachIndexed { order, cost ->
                    add(DesiredMaterialCost(GameCharacterCostType.TALENT, level, order, cost))
                }
            }
        }
        val materialsByGameId = findOrCreateMaterials(desired.map(DesiredMaterialCost::cost))
        val desiredByIdentity = desired.associateBy(DesiredMaterialCost::identity)
        entity.materialCosts.removeIf { stored -> stored.identity() !in desiredByIdentity }
        val existingByIdentity = entity.materialCosts.associateBy { it.identity() }

        desired.forEach { desiredCost ->
            val identity = desiredCost.identity()
            val stored = existingByIdentity[identity] ?: GameCharacterMaterialCost().also {
                it.character = entity
                it.costType = desiredCost.type
                it.level = desiredCost.level
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

    private fun synchronizeTalents(
        entity: GameCharacter,
        talents: List<CharacterTalent>,
    ) {
        val desiredKeys = talents.mapTo(linkedSetOf(), CharacterTalent::key)
        entity.talents.removeIf { it.key !in desiredKeys }
        val existingByKey = entity.talents.associateBy(GameCharacterTalent::key)

        talents.forEachIndexed { order, talent ->
            val stored = existingByKey[talent.key] ?: GameCharacterTalent().also {
                it.character = entity
                it.key = talent.key
                entity.talents += it
            }
            stored.kind = talent.kind
            stored.name = talent.name
            stored.description = talent.description
            stored.flavorText = talent.flavorText
            stored.displayOrder = order
            synchronizeTalentAttributes(stored, talent.attributes)
        }
    }

    private fun synchronizeStats(entity: GameCharacter, profile: CharacterStatProfile?) {
        val bases = profile?.baseStats.orEmpty().filterValues { it != 0.0 }
        entity.baseStats.removeIf { it.stat !in bases }
        val existingBases = entity.baseStats.associateBy { it.stat }
        bases.forEach { (stat, value) ->
            val stored = existingBases[stat] ?: GameCharacterBaseStat().also {
                it.character = entity
                it.stat = stat
                entity.baseStats += it
            }
            stored.value = value
            stored.growthCurve = profile?.growthCurves?.get(stat)
        }

        val levels = profile?.levelGrowth.orEmpty().flatMap { (level, values) ->
            values.map { (stat, value) -> (level to stat) to value }
        }.toMap()
        entity.levelStats.removeIf { (it.level to it.stat) !in levels }
        val existingLevels = entity.levelStats.associateBy { it.level to it.stat }
        levels.forEach { (identity, value) ->
            val stored = existingLevels[identity] ?: GameCharacterLevelStat().also {
                it.character = entity
                it.level = identity.first
                it.stat = identity.second
                entity.levelStats += it
            }
            stored.growth = value
        }

        val phases = profile?.ascensionBonuses.orEmpty().flatMap { (phase, values) ->
            values.map { (stat, value) -> (phase to stat) to value }
        }.toMap()
        entity.ascensionStats.removeIf { (it.phase to it.stat) !in phases }
        val existingPhases = entity.ascensionStats.associateBy { it.phase to it.stat }
        phases.forEach { (identity, value) ->
            val stored = existingPhases[identity] ?: GameCharacterAscensionStat().also {
                it.character = entity
                it.phase = identity.first
                it.stat = identity.second
                entity.ascensionStats += it
            }
            stored.bonus = value
        }
    }

    private fun synchronizeTalentAttributes(
        talent: GameCharacterTalent,
        attributes: List<CharacterTalentAttribute>,
    ) {
        talent.attributes.removeIf { it.displayOrder !in attributes.indices }
        val existingByOrder = talent.attributes.associateBy(GameCharacterTalentAttribute::displayOrder)
        attributes.forEachIndexed { order, attribute ->
            val stored = existingByOrder[order] ?: GameCharacterTalentAttribute().also {
                it.talent = talent
                it.displayOrder = order
                talent.attributes += it
            }
            stored.label = attribute.label
            synchronizeTalentAttributeValues(stored, attribute.values)
        }
    }

    private fun synchronizeTalentAttributeValues(
        attribute: GameCharacterTalentAttribute,
        values: List<String>,
    ) {
        attribute.values.removeIf { it.displayOrder !in values.indices }
        val existingByOrder = attribute.values
            .associateBy(GameCharacterTalentAttributeValue::displayOrder)
        values.forEachIndexed { order, value ->
            val stored = existingByOrder[order] ?: GameCharacterTalentAttributeValue().also {
                it.attribute = attribute
                it.displayOrder = order
                attribute.values += it
            }
            stored.value = value
        }
    }

    private fun toDefinition(entity: GameCharacter): CharacterDefinition? {
        if (entity.images.isEmpty()) return null
        return runCatching {
            CharacterDefinition(
                key = entity.key,
                id = entity.gameId,
                name = entity.name,
                title = entity.title,
                description = entity.description,
                weapon = entity.weapon,
                rarity = entity.rarity,
                birthday = entity.birthday,
                element = entity.element,
                affiliation = entity.affiliation,
                region = entity.region,
                constellation = entity.constellation,
                ascensionStatType = entity.ascensionStatType,
                stats = entity.baseStats.takeIf { it.isNotEmpty() }?.let { bases ->
                    CharacterStatProfile(
                        baseStats = bases.associate { it.stat to it.value },
                        growthCurves = bases.mapNotNull { base ->
                            base.growthCurve?.let { base.stat to it }
                        }.toMap(),
                        levelGrowth = entity.levelStats.groupBy { it.level }.mapValues { (_, values) ->
                            values.associate { it.stat to it.growth }
                        },
                        ascensionBonuses = entity.ascensionStats.groupBy { it.phase }.mapValues { (_, values) ->
                            values.associate { it.stat to it.bonus }
                        },
                    )
                },
                imageUrls = entity.images.mapNotNull { image ->
                    val url = image.localUrl
                    if (url == null) null else image.imageType to url
                }.toMap(),
                remoteImageUrls = entity.images.mapNotNull { image ->
                    val url = image.remoteUrl
                    if (url == null) null else image.imageType to url
                }.toMap(),
                ascensionCosts = entity.costs(GameCharacterCostType.ASCENSION),
                talentCosts = entity.costs(GameCharacterCostType.TALENT),
                talents = entity.talents.sortedBy(GameCharacterTalent::displayOrder).map { talent ->
                    CharacterTalent(
                        key = talent.key,
                        kind = talent.kind,
                        name = talent.name,
                        description = talent.description,
                        flavorText = talent.flavorText,
                        attributes = talent.attributes
                            .sortedBy(GameCharacterTalentAttribute::displayOrder)
                            .map { attribute ->
                                CharacterTalentAttribute(
                                    label = attribute.label,
                                    values = attribute.values
                                        .sortedBy(GameCharacterTalentAttributeValue::displayOrder)
                                        .map(GameCharacterTalentAttributeValue::value),
                                )
                            },
                    )
                },
                imageResourceKey = entity.imageResourceKey ?: entity.key,
                talentResourceKey = entity.talentResourceKey ?: entity.key,
                hoyolabEntryId = entity.hoyolabEntryId,
                hoyolabPageVersion = entity.hoyolabPageVersion,
                hoyolabDataVersion = entity.hoyolabDataVersion,
            )
        }.onFailure {
            logger.warn("Stored character data for '{}' is invalid", entity.key, it)
        }.getOrNull()
    }

    private fun GameCharacter.costs(
        type: GameCharacterCostType,
    ): Map<Int, List<MaterialCost>> =
        materialCosts.asSequence()
            .filter { it.costType == type }
            .groupBy(GameCharacterMaterialCost::level)
            .toSortedMap()
            .mapValues { (_, costs) ->
                costs.sortedBy(GameCharacterMaterialCost::materialOrder).map { cost ->
                    MaterialCost(
                        id = cost.material.gameId,
                        name = cost.material.name,
                        count = cost.amount,
                    )
                }
            }

    private fun GameCharacterMaterialCost.identity(): MaterialCostIdentity =
        MaterialCostIdentity(costType, level, material.gameId)

    private data class DesiredMaterialCost(
        val type: GameCharacterCostType,
        val level: Int,
        val order: Int,
        val cost: MaterialCost,
    ) {
        fun identity(): MaterialCostIdentity = MaterialCostIdentity(type, level, cost.id)
    }

    private data class MaterialCostIdentity(
        val type: GameCharacterCostType,
        val level: Int,
        val materialId: Int,
    )
}
