package de.tyro.genshinapp.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerWeapon
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder
import java.util.concurrent.ConcurrentHashMap

@Service
class WeaponCatalogService(
    objectMapper: ObjectMapper,
    private val catalogStore: WeaponCatalogStore? = null,
    private val fandomImageUrlResolver: FandomImageUrlResolver? = null,
    private val imageUrlRegistry: ImageUrlRegistry? = null,
) {
    private val definitionsByKey = ConcurrentHashMap<String, WeaponDefinition>()
    private val configuredNames: List<String> =
        ClassPathResource(CATALOG_RESOURCE).inputStream.use {
            objectMapper.readValue(it, object : TypeReference<List<String>>() {})
        }

    init {
        catalogStore?.getWeapons().orEmpty().forEach(::remember)
        configuredNames.forEach { name ->
            val key = GoodKeyNormalizer.normalize(name)
            if (!definitionsByKey.containsKey(key)) {
                remember(save(baseDefinition(key, name)))
            }
        }
        imageUrlRegistry?.registerWeaponDefaults(getWeapons().map(::imageDefault))
        imageUrlRegistry?.registerWeaponFullDefaults(getWeapons().map(::fullImageDefault))
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
        imageUrlRegistry?.registerWeaponDefaults(listOf(imageDefault(definition)))
        imageUrlRegistry?.registerWeaponFullDefaults(listOf(fullImageDefault(definition)))
        return definition
    }

    fun saveEnrichment(definition: WeaponDefinition): WeaponDefinition {
        val saved = remember(save(definition))
        imageUrlRegistry?.registerWeaponFullDefaults(listOf(fullImageDefault(saved)))
        return saved
    }

    fun rememberPersisted(definition: WeaponDefinition) {
        remember(definition)
        imageUrlRegistry?.registerWeaponDefaults(listOf(imageDefault(definition)))
        imageUrlRegistry?.registerWeaponFullDefaults(listOf(fullImageDefault(definition)))
    }

    fun imageUrl(key: String): String? {
        val normalizedKey = validKey(key) ?: return null
        find(normalizedKey) ?: return null
        return localImageUrl(key)
    }

    fun imageUrls(weapons: Collection<PlayerWeapon>): Map<String, String> =
        weapons.mapNotNull { weapon ->
            imageUrl(weapon.key)?.let { weapon.imageKey to it }
        }.toMap()

    fun fullImageUrl(key: String): String? {
        val normalizedKey = validKey(key) ?: return null
        val definition = find(normalizedKey) ?: return null
        val effectiveUrl = imageUrlRegistry?.weaponFullLink(normalizedKey)?.effectiveUrl
            ?: definition.fullImageUrl
        return localFullImageUrl(normalizedKey).takeIf { !effectiveUrl.isNullOrBlank() }
    }

    private fun baseDefinition(key: String, name: String): WeaponDefinition = WeaponDefinition(
        key = key,
        name = name,
        imageUrl = localImageUrl(key),
        remoteImageUrl = fandomImageUrlResolver?.weaponImageUrl(name),
    )

    private fun save(definition: WeaponDefinition): WeaponDefinition =
        catalogStore?.saveWeapon(definition) ?: definition

    private fun remember(definition: WeaponDefinition): WeaponDefinition {
        definitionsByKey[definition.key] = definition
        return definition
    }

    private fun imageDefault(definition: WeaponDefinition): WeaponImageDefault =
        WeaponImageDefault(
            key = definition.key,
            name = definition.name,
            defaultUrl = definition.remoteImageUrl.orEmpty(),
        )

    private fun fullImageDefault(definition: WeaponDefinition): WeaponFullImageDefault =
        WeaponFullImageDefault(
            key = definition.key,
            name = "${definition.name} full view",
            defaultUrl = definition.fullImageUrl.orEmpty(),
        )

    private fun localImageUrl(key: String): String =
        UriComponentsBuilder.fromPath("/media/weapons/{key}")
            .buildAndExpand(key)
            .encode()
            .toUriString()

    private fun localFullImageUrl(key: String): String =
        UriComponentsBuilder.fromPath("/media/weapons/{key}/full")
            .buildAndExpand(key)
            .encode()
            .toUriString()

    private fun validKey(key: String): String? = GoodKeyNormalizer.normalize(key)
        .takeIf { it.isNotBlank() && it.matches(WEAPON_KEY_PATTERN) }

    companion object {
        private const val CATALOG_RESOURCE = "data/weapon-names.json"
        private val WEAPON_KEY_PATTERN = Regex("[a-z0-9]+")
    }
}
