package de.tyro.genshinapp.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterImageType
import de.tyro.genshinapp.model.CharacterTalent
import de.tyro.genshinapp.model.CharacterTalentAttribute
import de.tyro.genshinapp.model.CharacterTalentKind
import de.tyro.genshinapp.model.MaterialCost
import de.tyro.genshinapp.model.MaterialDefinition
import de.tyro.genshinapp.model.TravelerAppearance
import de.tyro.genshinapp.model.TravelerElement
import de.tyro.genshinapp.model.TravelerIdentity
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@Service
class CharacterCatalogService(
    private val objectMapper: ObjectMapper,
    private val contentLoader: DynamicContentLoader,
    private val fandomImageUrlResolver: FandomImageUrlResolver,
    private val catalogStore: CharacterCatalogStore? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val configuredKeys = loadCharacterKeys()
    private val charactersByKey = ConcurrentHashMap<String, CharacterDefinition>()
    private val travelerAppearances = ConcurrentHashMap<String, CharacterDefinition>()
    private val travelerVariants = ConcurrentHashMap<String, CharacterDefinition>()
    private val materialsById = ConcurrentHashMap<Int, MaterialDefinition>()

    init {
        catalogStore?.getCharacters().orEmpty().forEach(::rememberCharacter)
        catalogStore?.getMaterials().orEmpty().forEach(::rememberMaterials)

        configuredKeys.forEach { key ->
            val storedCharacter = charactersByKey[key]
            val needsTalentRefresh = storedCharacter != null &&
                key !in TALENTLESS_CHARACTER_KEYS &&
                (
                    storedCharacter.talents.isEmpty() ||
                        storedCharacter.combatTalents
                            .filter { it.kind.progressField != null }
                            .any { it.attributes.isEmpty() }
                    )
            if (storedCharacter == null || needsTalentRefresh) {
                loadCharacterFromExistingSources(key)?.let { character ->
                    rememberCharacter(saveCharacter(character))
                }
            }
        }
        rememberMaterials(BASE_MATERIALS)
        rememberMaterials(loadBundledWeaponMaterials())
        val enrichedMaterials = MaterialCatalogMetadata.enrich(materialsById.values, getCharacters())
        materialsById.clear()
        rememberMaterials(enrichedMaterials)
        catalogStore?.saveMaterials(materialsById.values)
        contentLoader.registerDefaultImageLinks(getCharacters(), getMaterials())
    }

    fun getCharacters(): List<CharacterDefinition> {
        val configuredCharacters = configuredKeys.mapNotNull(charactersByKey::get)
        val dynamicallyLoadedCharacters = charactersByKey
            .filterKeys { it !in configuredKeys && it !in HIDDEN_CHARACTER_KEYS }
            .values
            .sortedBy { it.name }
        return configuredCharacters + dynamicallyLoadedCharacters
    }

    fun findCharacter(key: String): CharacterDefinition? {
        val requestedKey = key.trim().lowercase()
        val normalizedKey = if (TravelerIdentity.isTraveler(requestedKey)) {
            TravelerIdentity.KEY
        } else {
            requestedKey
        }
        charactersByKey[normalizedKey]?.let { return it }

        catalogStore?.findCharacter(normalizedKey)?.let { character ->
            rememberCharacter(character)
            contentLoader.registerDefaultImageLinks(
                listOf(character),
                materialsOf(listOf(character)),
            )
            return character
        }

        return loadCharacterFromExistingSources(normalizedKey)?.let { loadedCharacter ->
            val character = saveCharacter(loadedCharacter)
            rememberCharacter(character)
            contentLoader.registerDefaultImageLinks(
                listOf(character),
                materialsOf(listOf(character)),
            )
            character
        }
    }

    fun findTraveler(
        element: TravelerElement,
        appearance: TravelerAppearance,
    ): CharacterDefinition {
        val cacheKey = "${appearance.key}:${element.key}"
        return travelerVariants.computeIfAbsent(cacheKey) {
            val appearanceRoot = contentLoader.loadCharacterJson(appearance.characterKey)
                ?: throw IllegalStateException("Traveler appearance data is unavailable")
            val talentRoot = contentLoader.loadCharacterJson(element.variantKey)
            val character = mapCharacter(
                key = TravelerIdentity.KEY,
                root = appearanceRoot,
                displayName = "Traveler",
                element = element.displayName,
                imageSourceName = appearanceRoot.requiredText("name"),
                imageResourceKey = appearance.resourceKey,
                talentResourceKey = element.variantKey,
                talentsRoot = talentRoot?.path("talents"),
                remoteImageOverrides = travelerRemoteImageUrls(appearanceRoot),
            )
            rememberMaterials(materialsOf(listOf(character)))
            catalogStore?.saveMaterials(materialsOf(listOf(character)))
            contentLoader.registerDefaultImageLinks(
                listOf(character),
                materialsOf(listOf(character)),
            )
            character
        }
    }

    fun findTravelerAppearance(appearance: TravelerAppearance): CharacterDefinition =
        travelerAppearances.computeIfAbsent(appearance.key) {
            val root = contentLoader.loadCharacterJson(appearance.characterKey)
                ?: throw IllegalStateException("Traveler appearance data is unavailable")
            val character = mapCharacter(
                key = TravelerIdentity.KEY,
                root = root,
                displayName = "Traveler",
                element = "All Elements",
                imageSourceName = root.requiredText("name"),
                imageResourceKey = appearance.resourceKey,
                talentResourceKey = TravelerElement.ANEMO.variantKey,
                remoteImageOverrides = travelerRemoteImageUrls(root),
            )
            contentLoader.registerDefaultImageLinks(listOf(character), emptyList())
            character
        }

    fun findMediaCharacter(key: String): CharacterDefinition? {
        val appearance = TravelerAppearance.fromKey(key)
        if (appearance != null && key.lowercase().startsWith(TravelerIdentity.KEY)) {
            return findTravelerAppearance(appearance)
        }
        val element = TravelerElement.fromKey(key)
        if (element != null && key.lowercase().startsWith(TravelerIdentity.KEY)) {
            return findTraveler(element, TravelerAppearance.AETHER)
        }
        return findCharacter(key)
    }

    fun travelerAppearanceCharacters(): List<CharacterDefinition> =
        TravelerAppearance.entries.map { appearance ->
            findTravelerAppearance(appearance).copy(
                name = "Traveler (${appearance.key.replaceFirstChar(Char::uppercase)})",
            )
        }

    fun travelerElementCharacters(): List<CharacterDefinition> =
        TravelerElement.entries.map { element ->
            findTraveler(element, TravelerAppearance.AETHER).copy(
                name = element.queryName,
            )
        }

    fun getMaterials(): List<MaterialDefinition> =
        materialsById.values.sortedBy { it.name }

    fun findMaterial(id: Int): MaterialDefinition? =
        materialsById[id]
            ?: catalogStore?.findMaterial(id)?.also(::rememberMaterials)

    private fun loadCharacterKeys(): List<String> {
        val listResource = ClassPathResource("data/characters/char_list.json")
        require(listResource.exists()) { "Character list was not found in the application resources" }

        return listResource.inputStream.use {
            objectMapper.readValue(it, object : TypeReference<List<String>>() {})
        }.map(String::lowercase)
    }

    private fun loadCharacterFromExistingSources(key: String): CharacterDefinition? = runCatching {
        val sourceKey = if (key == TravelerIdentity.KEY) TravelerAppearance.AETHER.characterKey else key
        val root = contentLoader.loadCharacterJson(sourceKey)
            ?: throw IllegalStateException("Character data for $key is unavailable")
        if (key == TravelerIdentity.KEY) {
            mapCharacter(
                key = key,
                root = root,
                displayName = "Traveler",
                element = "All Elements",
                imageSourceName = root.requiredText("name"),
                imageResourceKey = TravelerAppearance.AETHER.resourceKey,
                talentResourceKey = TravelerElement.ANEMO.variantKey,
                remoteImageOverrides = travelerRemoteImageUrls(root),
            )
        } else {
            mapCharacter(key, root)
        }
    }.onFailure {
        logger.warn("Could not load character '{}'", key, it)
    }.getOrNull()

    private fun mapCharacter(
        key: String,
        root: JsonNode,
        displayName: String = root.requiredText("name"),
        element: String? = root.optionalText("elementText"),
        imageSourceName: String = displayName,
        imageResourceKey: String = key,
        talentResourceKey: String = key,
        talentsRoot: JsonNode? = null,
        remoteImageOverrides: Map<CharacterImageType, String> = emptyMap(),
    ): CharacterDefinition {
        val remoteImageUrls = CharacterImageType.entries.associateWith { type ->
            remoteImageOverrides[type]
                ?: fandomImageUrlResolver.characterImageUrl(imageSourceName, type)
        }
        val imageUrls = CharacterImageType.entries.associateWith { type ->
            UriComponentsBuilder.fromPath("/media/characters/{key}/{type}")
                .queryParam("source", Integer.toHexString(remoteImageUrls[type].orEmpty().hashCode()))
                .buildAndExpand(imageResourceKey, type.key)
                .encode()
                .toUriString()
        }

        return CharacterDefinition(
            key = key,
            id = root.path("id").asLong(),
            name = displayName,
            title = root.optionalText("title"),
            description = root.optionalText("description"),
            weapon = root.optionalText("weaponText"),
            rarity = root.path("rarity").asInt(),
            birthday = root.optionalText("birthday"),
            element = element,
            affiliation = root.optionalText("affiliation"),
            region = root.optionalText("region"),
            constellation = root.optionalText("constellation"),
            ascensionStatType = root.optionalText("substatType"),
            imageUrls = imageUrls,
            remoteImageUrls = remoteImageUrls,
            ascensionCosts = readCosts(root.path("costs"), "ascend"),
            talentCosts = readCosts((talentsRoot ?: root.path("talents")).path("costs"), "lvl"),
            talents = readTalents(talentsRoot ?: root.path("talents")),
            imageResourceKey = imageResourceKey,
            talentResourceKey = talentResourceKey,
        )
    }

    private fun readTalents(talentsNode: JsonNode): List<CharacterTalent> {
        if (!talentsNode.isObject) return emptyList()

        return TALENT_NODES.mapNotNull { (key, kind) ->
            val talentNode = talentsNode.path(key)
            val name = talentNode.optionalText("name") ?: return@mapNotNull null
            val description = talentNode.optionalText("description") ?: return@mapNotNull null
            CharacterTalent(
                key = key,
                kind = kind,
                name = name,
                description = description,
                flavorText = talentNode.optionalText("flavorText"),
                attributes = readTalentAttributes(talentNode.path("attributes")),
            )
        }
    }

    private fun travelerRemoteImageUrls(root: JsonNode): Map<CharacterImageType, String> {
        val images = root.path("images")
        return listOfNotNull(
            images.firstText("mihoyo_icon", "image")?.let { CharacterImageType.ICON to it },
            images.firstText("mihoyo_icon", "card")?.let { CharacterImageType.CARD to it },
            images.firstText("mihoyo_icon", "mihoyo_sideIcon", "portrait", "card")
                ?.let { CharacterImageType.WISH to it },
        ).toMap()
    }

    private fun readTalentAttributes(attributesNode: JsonNode): List<CharacterTalentAttribute> {
        val labels = attributesNode.path("labels")
        val parameters = attributesNode.path("parameters")
        if (!labels.isArray || !parameters.isObject) return emptyList()

        return labels.mapNotNull { labelNode ->
            val rawLabel = labelNode.asText()
            val separatorIndex = rawLabel.indexOf('|')
            if (separatorIndex < 1 || separatorIndex == rawLabel.lastIndex) {
                return@mapNotNull null
            }
            val label = rawLabel.substring(0, separatorIndex).trim()
            val valueTemplate = rawLabel.substring(separatorIndex + 1).trim()
            val parameterTokens = TALENT_PARAMETER.findAll(valueTemplate).toList()
            val levelCount = parameterTokens.maxOfOrNull { match ->
                parameters.path(match.groupValues[1]).takeIf(JsonNode::isArray)?.size() ?: 0
            } ?: 0
            if (label.isBlank() || levelCount == 0) return@mapNotNull null

            CharacterTalentAttribute(
                label = label,
                values = (0 until levelCount).map { levelIndex ->
                    TALENT_PARAMETER.replace(valueTemplate) { match ->
                        val valueNode = parameters.path(match.groupValues[1]).path(levelIndex)
                        if (valueNode.isNumber) {
                            formatTalentParameter(valueNode.asDouble(), match.groupValues[2])
                        } else {
                            "-"
                        }
                    }
                },
            )
        }
    }

    private fun formatTalentParameter(value: Double, format: String): String {
        val percentage = format.endsWith('P')
        val numberFormat = format.removeSuffix("P")
        val pattern = when (numberFormat) {
            "I" -> "0"
            "F1" -> "0.0"
            "F2" -> "0.00"
            else -> "0.##"
        }
        val formatter = DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT)).apply {
            roundingMode = RoundingMode.HALF_UP
        }
        val displayValue = if (percentage) value * 100 else value
        return formatter.format(displayValue) + if (percentage) "%" else ""
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

    private fun loadBundledWeaponMaterials(): List<MaterialDefinition> =
        BUNDLED_WEAPON_KEYS.asSequence().flatMap { key ->
            val resource = ClassPathResource("data/weapons/data/$key.json")
            if (!resource.exists()) return@flatMap emptySequence()
            resource.inputStream.use(objectMapper::readTree).path("costs")
                .flatMap { phase -> phase.map { cost ->
                    MaterialDefinition(cost.path("id").asInt(), cost.path("name").asText())
                } }
                .asSequence()
        }.filter { it.id > 0 && it.name.isNotBlank() }.distinctBy { it.id }.toList()

    fun materialImageUrl(id: Int): String? {
        if (id < 0) return null
        return UriComponentsBuilder.fromPath("/media/materials/{id}")
            .buildAndExpand(id)
            .encode()
            .toUriString()
    }

    private fun saveCharacter(character: CharacterDefinition): CharacterDefinition {
        val stored = catalogStore?.saveCharacter(character) ?: return character
        return stored.copy(
            imageResourceKey = character.imageResourceKey,
            talentResourceKey = character.talentResourceKey,
        )
    }

    private fun rememberCharacter(character: CharacterDefinition) {
        charactersByKey[character.key] = character
        rememberMaterials(materialsOf(listOf(character)))
    }

    private fun rememberMaterials(materials: Collection<MaterialDefinition>) {
        materials.forEach(::rememberMaterials)
    }

    private fun rememberMaterials(material: MaterialDefinition) {
        materialsById[material.id] = material
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

    private fun JsonNode.firstText(vararg fields: String): String? =
        fields.firstNotNullOfOrNull { field -> optionalText(field) }

    companion object {
        private val BASE_MATERIALS = listOf(
            MaterialDefinition(0, "Character EXP"),
            MaterialDefinition(104013, "Mystic Enhancement Ore"),
        )
        private val BUNDLED_WEAPON_KEYS = listOf("rust", "sacrificialbow")
        private val TALENTLESS_CHARACTER_KEYS = setOf(TravelerIdentity.KEY)
        private val HIDDEN_CHARACTER_KEYS = buildSet {
            add("aether")
            add("lumine")
            TravelerElement.entries.mapTo(this) { it.variantKey }
        }
        private val TALENT_NODES = listOf(
            "combat1" to CharacterTalentKind.NORMAL_ATTACK,
            "combat2" to CharacterTalentKind.ELEMENTAL_SKILL,
            "combat3" to CharacterTalentKind.ELEMENTAL_BURST,
            "combatsp" to CharacterTalentKind.SPECIAL_MOVEMENT,
            "combatju" to CharacterTalentKind.SPECIAL_MOVEMENT,
            "passive1" to CharacterTalentKind.PASSIVE,
            "passive2" to CharacterTalentKind.PASSIVE,
            "passive3" to CharacterTalentKind.PASSIVE,
            "passive4" to CharacterTalentKind.PASSIVE,
        )
        private val TALENT_PARAMETER = Regex("\\{(param\\d+):([A-Z0-9]+)}")
    }
}
