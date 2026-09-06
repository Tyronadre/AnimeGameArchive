package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.service.content.FandomImageUrlResolver
import de.tyro.genshinapp.service.content.ImageUrlRegistry
import de.tyro.genshinapp.service.content.WeaponImageDefault
import de.tyro.genshinapp.service.importer.HoyolabWeaponSummary
import de.tyro.genshinapp.startup.ApplicationStartupStatus
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerWeapon
import de.tyro.genshinapp.model.WeaponImageType
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder
import java.util.concurrent.ConcurrentHashMap

@Service
class WeaponCatalogService(
    private val catalogStore: WeaponCatalogStore? = null,
    private val fandomImageUrlResolver: FandomImageUrlResolver? = null,
    private val imageUrlRegistry: ImageUrlRegistry? = null,
) {
    private val definitionsByKey = ConcurrentHashMap<String, WeaponDefinition>()
    init {
        ApplicationStartupStatus.report(
            "Loading saved weapons…",
            "Refreshing weapon records and image defaults",
        )
        catalogStore?.getWeapons().orEmpty().forEach { stored ->
            remember(save(refreshDefaultImageUrl(stored)))
        }
        imageUrlRegistry?.registerWeaponDefaults(getWeapons().flatMap(::imageDefaults))
    }

    fun getWeapons(): List<WeaponDefinition> = definitionsByKey.values.sortedBy { it.name }

    fun find(key: String): WeaponDefinition? =
        definitionsByKey[GoodKeyNormalizer.normalize(key)]
            ?: catalogStore?.findWeapon(key)?.also(::remember)

    fun officialName(key: String): String? = find(key)?.name

    fun weaponName(key: String): String? {
        val normalizedKey = validKey(key) ?: return null
        return find(normalizedKey)?.name ?: GoodKeyNormalizer.humanize(key)
    }

    fun ensureWeapons(keys: Collection<String>) {
        keys.asSequence()
            .mapNotNull(::validKey)
            .distinct()
            .forEach(::ensureWeapon)
    }

    fun ensureWeapon(key: String): WeaponDefinition? {
        val normalizedKey = validKey(key) ?: return null
        find(normalizedKey)?.let { return it }
        val definition = remember(
            save(baseDefinition(normalizedKey, GoodKeyNormalizer.humanize(key))),
        )
        imageUrlRegistry?.registerWeaponDefaults(imageDefaults(definition))
        return definition
    }

    /** Projects current official HoYoWiki weapon identities onto the persisted catalogue. */
    @Synchronized
    fun synchronizeHoyolabWeaponList(entries: Collection<HoyolabWeaponSummary>): Int {
        var changedCount = 0
        entries.forEach { entry ->
            val key = GoodKeyNormalizer.normalize(entry.name)
            if (key.isBlank()) return@forEach
            val current = find(key) ?: baseDefinition(key, entry.name)
            val candidate = current.copy(
                name = entry.name,
                rarity = entry.rarity ?: current.rarity,
                weaponType = entry.weaponType ?: current.weaponType,
                secondaryStatType = entry.secondaryStatType ?: current.secondaryStatType,
                description = entry.description ?: current.description,
                hoyolabEntryId = entry.entryPageId,
                remoteImageUrls = current.remoteImageUrls +
                    (WeaponImageType.ICON to entry.iconUrl),
            )
            if (candidate != current) {
                saveEnrichment(candidate)
                changedCount++
            } else {
                remember(current)
            }
        }
        return changedCount
    }

    fun saveEnrichment(definition: WeaponDefinition): WeaponDefinition {
        val saved = remember(save(definition))
        imageUrlRegistry?.registerWeaponDefaults(imageDefaults(saved))
        return saved
    }

    fun rememberPersisted(definition: WeaponDefinition) {
        remember(definition)
        imageUrlRegistry?.registerWeaponDefaults(imageDefaults(definition))
    }

    fun imageUrl(key: String): String? = imageUrl(key, WeaponImageType.ICON)

    fun imageUrl(key: String, imageType: WeaponImageType): String? {
        val normalizedKey = validKey(key) ?: return null
        val definition = find(normalizedKey) ?: return null
        val effectiveUrl = imageUrlRegistry?.weaponLink(normalizedKey, imageType)?.effectiveUrl
            ?: definition.remoteImageUrl(imageType)
        return localImageUrl(normalizedKey, imageType).takeIf { !effectiveUrl.isNullOrBlank() }
    }

    fun imageUrls(weapons: Collection<PlayerWeapon>): Map<String, String> =
        weapons.mapNotNull { weapon ->
            imageUrl(weapon.key)?.let { weapon.imageKey to it }
        }.toMap()

    fun weaponTypeImageUrl(type: String?): String? = type
        ?.let(GoodKeyNormalizer::normalize)
        ?.let(TYPE_ICON_WEAPON_KEYS::get)
        ?.let(::imageUrl)

    fun fullImageUrl(key: String): String? = imageUrl(key, WeaponImageType.FULL_ASCENDED)

    fun unascendedImageUrl(key: String): String? =
        imageUrl(key, WeaponImageType.FULL_UNASCENDED)

    private fun baseDefinition(key: String, name: String): WeaponDefinition = WeaponDefinition(
        key = key,
        name = name,
        imageUrls = WeaponImageType.entries.associateWith { localImageUrl(key, it) },
        remoteImageUrls = fandomImageUrlResolver?.weaponImageUrl(name)?.let {
            mapOf(WeaponImageType.ICON to it)
        }.orEmpty(),
    )

    private fun save(definition: WeaponDefinition): WeaponDefinition =
        catalogStore?.saveWeapon(definition) ?: definition

    private fun remember(definition: WeaponDefinition): WeaponDefinition {
        // Stored remoteImageUrl values are snapshots of generated defaults. Recalculate them when
        // loading the runtime catalog so resolver changes are reflected on the next app start.
        // User overrides live independently in ImageUrlRegistry and are therefore preserved.
        val refreshedDefinition = refreshDefaultImageUrl(definition)
        definitionsByKey[refreshedDefinition.key] = refreshedDefinition
        return refreshedDefinition
    }

    private fun imageDefaults(definition: WeaponDefinition): List<WeaponImageDefault> =
        WeaponImageType.entries.map { imageType ->
            WeaponImageDefault(
                key = definition.key,
                imageType = imageType,
                name = "${definition.name} ${imageType.label}",
                defaultUrl = currentDefaultImageUrl(definition, imageType),
            )
        }

    private fun refreshDefaultImageUrl(definition: WeaponDefinition): WeaponDefinition {
        val defaultUrl = currentDefaultImageUrl(definition, WeaponImageType.ICON)
        return if (definition.remoteImageUrl(WeaponImageType.ICON).orEmpty() == defaultUrl) {
            definition
        } else {
            definition.copy(
                remoteImageUrls = definition.remoteImageUrls +
                    (WeaponImageType.ICON to defaultUrl),
            )
        }
    }

    private fun currentDefaultImageUrl(
        definition: WeaponDefinition,
        imageType: WeaponImageType,
    ): String = if (imageType == WeaponImageType.ICON) {
        definition.remoteImageUrl(imageType)
            .takeIf { definition.hoyolabEntryId != null && !it.isNullOrBlank() }
            ?: fandomImageUrlResolver?.weaponImageUrl(definition.name)
            ?: definition.remoteImageUrl(imageType).orEmpty()
    } else {
        definition.remoteImageUrl(imageType).orEmpty()
    }

    private fun localImageUrl(key: String, imageType: WeaponImageType): String =
        UriComponentsBuilder.fromPath("/media/weapons/{key}/{type}")
            .buildAndExpand(key, imageType.key)
            .encode()
            .toUriString()

    private fun validKey(key: String): String? = GoodKeyNormalizer.normalize(key)
        .takeIf { it.isNotBlank() && it.matches(WEAPON_KEY_PATTERN) }

    companion object {
        private val WEAPON_KEY_PATTERN = Regex("[a-z0-9]+")
        private val TYPE_ICON_WEAPON_KEYS = mapOf(
            "sword" to "silversword",
            "claymore" to "oldmercspal",
            "polearm" to "ironpoint",
            "catalyst" to "pocketgrimoire",
            "bow" to "seasonedhuntersbow",
        )
    }
}
