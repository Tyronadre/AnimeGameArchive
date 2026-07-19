package de.tyro.genshinapp.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.entity.GameCharacter
import de.tyro.genshinapp.entity.Material
import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterImageType
import de.tyro.genshinapp.model.CharacterTalent
import de.tyro.genshinapp.model.MaterialCost
import de.tyro.genshinapp.model.MaterialDefinition
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.repository.GameCharacterRepository
import de.tyro.genshinapp.repository.MaterialRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface CharacterCatalogStore {
    fun getCharacters(): List<CharacterDefinition>

    fun findCharacter(key: String): CharacterDefinition?

    fun saveCharacter(character: CharacterDefinition): CharacterDefinition

    fun getMaterials(): List<MaterialDefinition>

    fun findMaterial(id: Int): MaterialDefinition?

    fun saveMaterials(materials: Collection<MaterialDefinition>)
}

@Service
class JpaCharacterCatalogStore(
    private val objectMapper: ObjectMapper,
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
        materialDefinitions(character).forEach(::upsertMaterial)
        val entity = characterRepository.findByKey(character.key)
            ?: GameCharacter().also { it.key = character.key }

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
        entity.imageUrlsJson = writeImageUrls(character.imageUrls)
        entity.remoteImageUrlsJson = writeImageUrls(character.remoteImageUrls)
        entity.ascensionCostsJson = writeCosts(character.ascensionCosts)
        entity.talentCostsJson = writeCosts(character.talentCosts)
        entity.talentsJson = objectMapper.writeValueAsString(character.talents)

        return toDefinition(characterRepository.save(entity)) ?: character
    }

    @Transactional(readOnly = true)
    override fun getMaterials(): List<MaterialDefinition> =
        materialRepository.findAllByOrderByNameAsc().map(::toDefinition)

    @Transactional(readOnly = true)
    override fun findMaterial(id: Int): MaterialDefinition? =
        materialRepository.findByGameId(id)?.let(::toDefinition)

    @Transactional
    override fun saveMaterials(materials: Collection<MaterialDefinition>) {
        materials.forEach(::upsertMaterial)
    }

    private fun upsertMaterial(material: MaterialDefinition): Material {
        val entity = materialRepository.findByGameId(material.id)
            ?: Material().also { it.gameId = material.id }
        entity.name = material.name
        entity.type = material.category.name
        entity.craftingFamily = material.craftingFamily
        entity.craftingTier = material.craftingTier
        entity.conversionGroup = material.conversionGroup
        return materialRepository.save(entity)
    }

    private fun toDefinition(entity: GameCharacter): CharacterDefinition? =
        runCatching {
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
                imageUrls = readImageUrls(entity.imageUrlsJson),
                remoteImageUrls = readImageUrls(entity.remoteImageUrlsJson),
                ascensionCosts = readCosts(entity.ascensionCostsJson),
                talentCosts = readCosts(entity.talentCostsJson),
                talents = readTalents(entity.talentsJson),
            )
        }.onFailure {
            logger.warn("Stored character data for '{}' is invalid", entity.key, it)
        }.getOrNull()

    private fun toDefinition(entity: Material): MaterialDefinition =
        MaterialDefinition(
            id = entity.gameId,
            name = entity.name,
            category = runCatching { MaterialCategory.valueOf(entity.type.orEmpty()) }
                .getOrDefault(MaterialCategory.OTHER),
            craftingFamily = entity.craftingFamily,
            craftingTier = entity.craftingTier,
            conversionGroup = entity.conversionGroup,
        )

    private fun materialDefinitions(character: CharacterDefinition): List<MaterialDefinition> =
        MaterialCatalogMetadata.enrich(
            (character.ascensionCosts.values.flatten() + character.talentCosts.values.flatten())
                .asSequence()
                .filter { it.id > 0 }
                .distinctBy { it.id }
                .map { MaterialDefinition(it.id, it.name) }
                .toList(),
            listOf(character),
        )

    private fun writeCosts(costs: Map<Int, List<MaterialCost>>): String =
        objectMapper.writeValueAsString(costs)

    private fun readCosts(json: String): Map<Int, List<MaterialCost>> =
        objectMapper.readValue(json, COSTS_TYPE)

    private fun readTalents(json: String?): List<CharacterTalent> =
        json?.takeIf(String::isNotBlank)
            ?.let { objectMapper.readValue(it, TALENTS_TYPE) }
            .orEmpty()

    private fun writeImageUrls(imageUrls: Map<CharacterImageType, String>): String =
        objectMapper.writeValueAsString(
            imageUrls.mapKeys { (imageType, _) -> imageType.key },
        )

    private fun readImageUrls(json: String): Map<CharacterImageType, String> =
        objectMapper.readValue(json, IMAGE_URLS_TYPE)
            .mapNotNull { (key, url) ->
                CharacterImageType.fromKey(key)?.let { it to url }
            }
            .toMap()

    companion object {
        private val COSTS_TYPE = object : TypeReference<Map<Int, List<MaterialCost>>>() {}
        private val TALENTS_TYPE = object : TypeReference<List<CharacterTalent>>() {}
        private val IMAGE_URLS_TYPE = object : TypeReference<Map<String, String>>() {}
    }
}
