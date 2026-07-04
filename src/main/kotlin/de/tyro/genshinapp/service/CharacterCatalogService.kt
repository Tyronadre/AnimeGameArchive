package de.tyro.genshinapp.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterImageType
import de.tyro.genshinapp.model.MaterialCost
import de.tyro.genshinapp.model.MaterialDefinition
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder
import java.util.concurrent.ConcurrentHashMap

@Service
class CharacterCatalogService(
    private val objectMapper: ObjectMapper,
    private val contentLoader: DynamicContentLoader,
    private val fandomImageUrlResolver: FandomImageUrlResolver,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val configuredKeys = loadCharacterKeys()
    private val charactersByKey = ConcurrentHashMap<String, CharacterDefinition>()

    init {
        configuredKeys.forEach { key ->
            loadCharacter(key)?.let { charactersByKey[key] = it }
        }
        contentLoader.registerDefaultImageLinks(getCharacters(), getMaterials())
    }

    fun getCharacters(): List<CharacterDefinition> {
        val configuredCharacters = configuredKeys.mapNotNull(charactersByKey::get)
        val dynamicallyLoadedCharacters = charactersByKey
            .filterKeys { it !in configuredKeys }
            .values
            .sortedBy { it.name }
        return configuredCharacters + dynamicallyLoadedCharacters
    }

    fun findCharacter(key: String): CharacterDefinition? {
        val normalizedKey = key.trim().lowercase()
        charactersByKey[normalizedKey]?.let { return it }

        return loadCharacter(normalizedKey)?.also { character ->
            charactersByKey.putIfAbsent(normalizedKey, character)
            contentLoader.registerDefaultImageLinks(
                listOf(character),
                materialsOf(listOf(character)),
            )
        }
    }

    fun getMaterials(): List<MaterialDefinition> =
        (
            materialsOf(getCharacters()) +
                MaterialDefinition(0, "Character EXP") +
                MaterialDefinition(104013, "Mystic Enhancement Ore")
            )
            .distinctBy { it.id }
            .sortedBy { it.name }

    fun findMaterial(id: Int): MaterialDefinition? = getMaterials().find { it.id == id }

    private fun loadCharacterKeys(): List<String> {
        val listResource = ClassPathResource("data/characters/char_list.json")
        require(listResource.exists()) { "Character list was not found in the application resources" }

        return listResource.inputStream.use {
            objectMapper.readValue(it, object : TypeReference<List<String>>() {})
        }.map(String::lowercase)
    }

    private fun loadCharacter(key: String): CharacterDefinition? = runCatching {
        val root = contentLoader.loadCharacterJson(key)
            ?: throw IllegalStateException("Character data for $key is unavailable")
        mapCharacter(key, root)
    }.onFailure {
        logger.warn("Could not load character '{}'", key, it)
    }.getOrNull()

    private fun mapCharacter(key: String, root: JsonNode): CharacterDefinition {
        val name = root.requiredText("name")
        val remoteImageUrls = CharacterImageType.entries.associateWith { type ->
            fandomImageUrlResolver.characterImageUrl(name, type)
        }
        val imageUrls = CharacterImageType.entries.associateWith { type ->
            UriComponentsBuilder.fromPath("/media/characters/{key}/{type}")
                .buildAndExpand(key, type.key)
                .encode()
                .toUriString()
        }

        return CharacterDefinition(
            key = key,
            id = root.path("id").asLong(),
            name = name,
            title = root.optionalText("title"),
            description = root.optionalText("description"),
            weapon = root.optionalText("weaponText"),
            rarity = root.path("rarity").asInt(),
            birthday = root.optionalText("birthday"),
            element = root.optionalText("elementText"),
            affiliation = root.optionalText("affiliation"),
            region = root.optionalText("region"),
            constellation = root.optionalText("constellation"),
            ascensionStatType = root.optionalText("substatType"),
            imageUrls = imageUrls,
            remoteImageUrls = remoteImageUrls,
            ascensionCosts = readCosts(root.path("costs"), "ascend"),
            talentCosts = readCosts(root.path("talents").path("costs"), "lvl"),
        )
    }

    private fun readCosts(costsNode: JsonNode, prefix: String): Map<Int, List<MaterialCost>> {
        if (!costsNode.isObject) return emptyMap()

        return costsNode.properties()
            .mapNotNull { (key, costs) ->
                val level = key.removePrefix(prefix).toIntOrNull() ?: return@mapNotNull null
                level to costs.map { cost ->
                    MaterialCost(
                        id = cost.path("id").asInt(),
                        name = cost.requiredText("name"),
                        count = cost.path("count").asLong(),
                    )
                }
            }
            .sortedBy { it.first }
            .toMap()
    }

    fun materialImageUrl(id: Int): String? {
        if (id < 0) return null
        return UriComponentsBuilder.fromPath("/media/materials/{id}")
            .buildAndExpand(id)
            .encode()
            .toUriString()
    }

    private fun materialsOf(
        characters: Collection<CharacterDefinition>,
    ): List<MaterialDefinition> = characters
        .asSequence()
        .flatMap { character ->
            (character.ascensionCosts.values.flatten() + character.talentCosts.values.flatten()).asSequence()
        }
        .filter { it.id > 0 }
        .distinctBy { it.id }
        .map { MaterialDefinition(it.id, it.name) }
        .sortedBy { it.name }
        .toList()

    private fun JsonNode.requiredText(field: String): String =
        path(field).takeIf { it.isTextual }?.asText()
            ?: throw IllegalArgumentException("Required text field '$field' is missing")

    private fun JsonNode.optionalText(field: String): String? =
        path(field).takeIf { it.isTextual && !it.asText().isBlank() }?.asText()
}
