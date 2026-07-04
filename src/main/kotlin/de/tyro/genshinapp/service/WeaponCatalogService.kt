package de.tyro.genshinapp.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerWeapon
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder

@Service
class WeaponCatalogService(
    objectMapper: ObjectMapper,
) {
    private val namesByKey: Map<String, String> =
        ClassPathResource(CATALOG_RESOURCE).inputStream.use {
            objectMapper.readValue(it, object : TypeReference<List<String>>() {})
        }.associateBy(GoodKeyNormalizer::normalize)

    fun officialName(key: String): String? = namesByKey[GoodKeyNormalizer.normalize(key)]

    fun weaponName(key: String): String? {
        val normalizedKey = GoodKeyNormalizer.normalize(key)
            .takeIf { it.isNotBlank() && it.matches(WEAPON_KEY_PATTERN) }
            ?: return null
        return namesByKey[normalizedKey] ?: GoodKeyNormalizer.humanize(key)
    }

    fun imageUrl(key: String): String? {
        if (weaponName(key) == null) return null
        return UriComponentsBuilder.fromPath("/media/weapons/{key}")
            .buildAndExpand(key)
            .encode()
            .toUriString()
    }

    fun imageUrls(weapons: Collection<PlayerWeapon>): Map<String, String> =
        weapons.mapNotNull { weapon ->
            imageUrl(weapon.key)?.let { weapon.imageKey to it }
        }.toMap()

    companion object {
        private const val CATALOG_RESOURCE = "data/weapon-names.json"
        private val WEAPON_KEY_PATTERN = Regex("[a-z0-9]+")
    }
}
