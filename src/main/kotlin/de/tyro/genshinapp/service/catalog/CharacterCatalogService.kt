package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.service.content.DynamicContentLoader
import de.tyro.genshinapp.service.content.FandomImageUrlResolver
import de.tyro.genshinapp.service.importer.HoyolabCharacterSummary
import de.tyro.genshinapp.startup.ApplicationStartupStatus
import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterImageType
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.TravelerAppearance
import de.tyro.genshinapp.model.TravelerElement
import de.tyro.genshinapp.model.TravelerIdentity
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder
import java.util.concurrent.ConcurrentHashMap

@Service
class CharacterCatalogService(
    private val contentLoader: DynamicContentLoader,
    private val fandomImageUrlResolver: FandomImageUrlResolver,
    private val catalogStore: CharacterCatalogStore? = null,
) {
    private val charactersByKey = ConcurrentHashMap<String, CharacterDefinition>()
    private val travelerAppearances = ConcurrentHashMap<String, CharacterDefinition>()
    private val travelerVariants = ConcurrentHashMap<String, CharacterDefinition>()

    init {
        ApplicationStartupStatus.report(
            "Loading saved characters…",
            "Refreshing character records and image defaults",
        )
        catalogStore?.let { store ->
            store.getCharacters().forEach { storedCharacter ->
                val refreshedCharacter = rememberCharacter(storedCharacter)
                if (refreshedCharacter.hasDifferentImageUrlsThan(storedCharacter)) {
                    store.saveCharacter(refreshedCharacter)
                }
            }
        }
        contentLoader.registerDefaultImageLinks(getCharacters(), emptyList())
    }

    fun getCharacters(): List<CharacterDefinition> {
        return charactersByKey
            .filterKeys { it !in HIDDEN_CHARACTER_KEYS }
            .values
            .sortedBy { it.name }
    }

    fun findCharacter(key: String): CharacterDefinition? {
        val requestedKey = GoodKeyNormalizer.normalize(key)
        val normalizedKey = if (TravelerIdentity.isTraveler(requestedKey)) {
            TravelerIdentity.KEY
        } else {
            requestedKey
        }
        charactersByKey[normalizedKey]?.let { return it }

        val store = catalogStore
        store?.findCharacter(normalizedKey)?.let { storedCharacter ->
            val character = rememberCharacter(storedCharacter)
            if (character.hasDifferentImageUrlsThan(storedCharacter)) {
                store.saveCharacter(character)
            }
            contentLoader.registerDefaultImageLinks(
                listOf(character),
                emptyList(),
            )
            return character
        }

        return null
    }

    fun findExactCharacter(key: String): CharacterDefinition? {
        val normalizedKey = GoodKeyNormalizer.normalize(key)
        return charactersByKey[normalizedKey]
            ?: catalogStore?.findCharacter(normalizedKey)?.let(::rememberCharacter)
    }

    fun findTraveler(
        element: TravelerElement,
        appearance: TravelerAppearance,
    ): CharacterDefinition {
        val cacheKey = "${appearance.key}:${element.key}"
        return travelerVariants.computeIfAbsent(cacheKey) {
            val appearanceCharacter = findExactCharacter(TravelerIdentity.KEY)
                ?: throw IllegalStateException("HoYoWiki Traveler data is unavailable")
            val elementCharacter = findExactCharacter(element.variantKey) ?: appearanceCharacter
            val remoteImageUrls = if (appearance == TravelerAppearance.AETHER) {
                appearanceCharacter.remoteImageUrls
            } else {
                defaultCharacterImageUrls(appearance.resourceKey, appearance.key)
            }
            val character = appearanceCharacter.copy(
                key = TravelerIdentity.KEY,
                name = "Traveler",
                element = element.displayName,
                ascensionStatType = elementCharacter.ascensionStatType,
                talents = elementCharacter.talents,
                talentCosts = elementCharacter.talentCosts,
                talentResourceKey = element.variantKey,
                imageResourceKey = appearance.resourceKey,
                remoteImageUrls = remoteImageUrls,
                imageUrls = localCharacterImageUrls(appearance.resourceKey, remoteImageUrls),
            )
            contentLoader.registerDefaultImageLinks(
                listOf(character),
                emptyList(),
            )
            character
        }
    }

    fun findTravelerAppearance(appearance: TravelerAppearance): CharacterDefinition =
        travelerAppearances.computeIfAbsent(appearance.key) {
            val base = findExactCharacter(TravelerIdentity.KEY)
                ?: throw IllegalStateException("HoYoWiki Traveler data is unavailable")
            val remoteImageUrls = if (appearance == TravelerAppearance.AETHER) {
                base.remoteImageUrls
            } else {
                defaultCharacterImageUrls(appearance.resourceKey, appearance.key)
            }
            val character = base.copy(
                key = TravelerIdentity.KEY,
                name = "Traveler",
                element = "All Elements",
                imageResourceKey = appearance.resourceKey,
                talentResourceKey = TravelerElement.ANEMO.variantKey,
                remoteImageUrls = remoteImageUrls,
                imageUrls = localCharacterImageUrls(appearance.resourceKey, remoteImageUrls),
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

    @Synchronized
    fun synchronizeHoyolabCharacterList(
        entries: Collection<HoyolabCharacterSummary>,
    ): Int {
        var changedCount = 0
        entries.forEach { entry ->
            val name = entry.name.trim()
            val key = GoodKeyNormalizer.normalize(name)
            if (key.isBlank()) return@forEach

            val current = charactersByKey[key]
            val imageResourceKey = current?.imageResourceKey ?: key
            val remoteImageUrls = defaultCharacterImageUrls(imageResourceKey, name)
            val candidate = (current ?: CharacterDefinition(
                key = key,
                id = 0,
                name = name,
                title = null,
                description = null,
                weapon = null,
                rarity = 0,
                birthday = null,
                element = null,
                affiliation = null,
                region = null,
                constellation = null,
                ascensionStatType = null,
                imageUrls = emptyMap(),
                remoteImageUrls = emptyMap(),
                ascensionCosts = emptyMap(),
                talentCosts = emptyMap(),
            )).copy(
                name = name,
                description = entry.description ?: current?.description,
                weapon = entry.weapon ?: current?.weapon,
                rarity = entry.rarity?.takeIf { it in 1..5 } ?: current?.rarity ?: 0,
                element = entry.element ?: current?.element,
                region = entry.region ?: current?.region,
                ascensionStatType = entry.ascensionStat ?: current?.ascensionStatType,
                remoteImageUrls = remoteImageUrls,
                imageUrls = localCharacterImageUrls(imageResourceKey, remoteImageUrls),
                hoyolabEntryId = entry.entryPageId,
            )
            if (candidate != current) {
                rememberCharacter(saveCharacter(candidate))
                changedCount++
            }
        }

        contentLoader.registerDefaultImageLinks(getCharacters(), emptyList())
        return changedCount
    }

    @Synchronized
    fun saveHoyolabEnrichment(character: CharacterDefinition): CharacterDefinition {
        val saved = rememberCharacter(saveCharacter(character))
        contentLoader.registerDefaultImageLinks(listOf(saved), emptyList())
        travelerAppearances.clear()
        travelerVariants.clear()
        return saved
    }

    private fun defaultCharacterImageUrls(
        imageResourceKey: String,
        imageSourceName: String,
    ): Map<CharacterImageType, String> =
        CharacterImageType.entries.associateWith { imageType ->
            internalCharacterImageDefaultOverride(imageResourceKey, imageType)
                ?: calculatedFandomCharacterImageUrl(imageSourceName, imageType)
        }

    private fun calculatedFandomCharacterImageUrl(
        imageSourceName: String,
        imageType: CharacterImageType,
    ): String = fandomImageUrlResolver.characterImageUrl(imageSourceName, imageType)

    private fun internalCharacterImageDefaultOverride(
        imageResourceKey: String,
        imageType: CharacterImageType,
    ): String? = CHARACTER_IMAGE_DEFAULT_OVERRIDES[
        CharacterImageDefaultKey(imageResourceKey, imageType),
    ]

    internal fun localCharacterImageUrls(
        imageResourceKey: String,
        remoteImageUrls: Map<CharacterImageType, String>,
    ): Map<CharacterImageType, String> =
        CharacterImageType.entries.associateWith { imageType ->
            UriComponentsBuilder.fromPath("/media/characters/{key}/{type}")
                .queryParam(
                    "source",
                    Integer.toHexString(remoteImageUrls[imageType].orEmpty().hashCode()),
                )
                .buildAndExpand(imageResourceKey, imageType.key)
                .encode()
                .toUriString()
        }

    private fun refreshCharacterImageUrls(character: CharacterDefinition): CharacterDefinition {
        val remoteImageUrls = defaultCharacterImageUrls(
            character.imageResourceKey,
            defaultCharacterImageSourceName(character),
        )
        return character.copy(
            remoteImageUrls = remoteImageUrls,
            imageUrls = localCharacterImageUrls(character.imageResourceKey, remoteImageUrls),
        )
    }

    private fun defaultCharacterImageSourceName(character: CharacterDefinition): String =
        when (character.imageResourceKey) {
            TravelerAppearance.AETHER.resourceKey -> AETHER_IMAGE_SOURCE_NAME
            TravelerAppearance.LUMINE.resourceKey -> LUMINE_IMAGE_SOURCE_NAME
            else -> character.name
        }

    private fun CharacterDefinition.hasDifferentImageUrlsThan(
        other: CharacterDefinition,
    ): Boolean =
        imageUrls != other.imageUrls || remoteImageUrls != other.remoteImageUrls

    private fun saveCharacter(character: CharacterDefinition): CharacterDefinition {
        val stored = catalogStore?.saveCharacter(character) ?: return character
        return stored.copy(
            imageResourceKey = character.imageResourceKey,
            talentResourceKey = character.talentResourceKey,
        )
    }

    private fun rememberCharacter(character: CharacterDefinition): CharacterDefinition {
        // Generated URLs are runtime defaults, not user choices. Recalculate them whenever a
        // stored character enters the catalog so resolver changes take effect after a restart.
        // Administrator overrides remain separate in ImageUrlRegistry.
        val refreshedCharacter = refreshCharacterImageUrls(character)
        charactersByKey[refreshedCharacter.key] = refreshedCharacter
        return refreshedCharacter
    }

    private data class CharacterImageDefaultKey(
        val imageResourceKey: String,
        val imageType: CharacterImageType,
    )

    companion object {
        private const val AETHER_IMAGE_SOURCE_NAME = "Aether"
        private const val LUMINE_IMAGE_SOURCE_NAME = "Lumine"
        private const val WIKIA_IMAGE_BASE_URL =
            "https://static.wikia.nocookie.net/"
        private val CHARACTER_IMAGE_DEFAULT_OVERRIDES = mapOf(
            characterImageDefaultOverride(
                TravelerAppearance.AETHER.resourceKey,
                CharacterImageType.CARD,
                wikiaImage("gensin-impact/images/0/0d/Traveler_Male_Card.png"),
            ),
            characterImageDefaultOverride(
                TravelerAppearance.AETHER.resourceKey,
                CharacterImageType.WISH,
                wikiaImage("topstrongest/images/0/0f/TravelersInfo.jpg"),
            ),
            characterImageDefaultOverride(
                TravelerAppearance.LUMINE.resourceKey,
                CharacterImageType.CARD,
                wikiaImage("gensin-impact/images/d/d2/Traveler_Female_Card.png"),
            ),
            characterImageDefaultOverride(
                TravelerAppearance.LUMINE.resourceKey,
                CharacterImageType.WISH,
                wikiaImage("topstrongest/images/0/0f/TravelersInfo.jpg"),
            ),
        )
        private val HIDDEN_CHARACTER_KEYS = buildSet {
            add("aether")
            add("lumine")
            TravelerElement.entries.mapTo(this) { it.variantKey }
        }
        private fun characterImageDefaultOverride(
            imageResourceKey: String,
            imageType: CharacterImageType,
            url: String,
        ): Pair<CharacterImageDefaultKey, String> =
            CharacterImageDefaultKey(imageResourceKey, imageType) to url

        private fun wikiaImage(path: String): String =
            "$WIKIA_IMAGE_BASE_URL/$path"
    }
}
