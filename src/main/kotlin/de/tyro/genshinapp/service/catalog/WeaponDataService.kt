 package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.model.FightProperty
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.MaterialCost
import de.tyro.genshinapp.model.MaterialDefinition
import de.tyro.genshinapp.model.WeaponImageType
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class WeaponDataService(
    private val catalogStore: WeaponCatalogStore? = null,
    private val weaponCatalogService: WeaponCatalogService? = null,
) {
    private val definitions = ConcurrentHashMap<String, WeaponDefinition>()

    fun find(key: String): WeaponDefinition? {
        val normalizedKey = GoodKeyNormalizer.normalize(key)
        definitions[normalizedKey]
            ?.takeIf { !it.weaponType.isNullOrBlank() }
            ?.let { return it }
        val stored = catalogStore?.findWeapon(normalizedKey)
        if (stored != null) {
            definitions[normalizedKey] = stored
            return stored
        }
        return null
    }

    fun findKnownMaterial(id: Int): MaterialDefinition? =
        (definitions.values + catalogStore?.getWeapons().orEmpty()).asSequence()
            .flatMap { it.ascensionCosts.values.flatten().asSequence() }
            .find { it.id == id }
            ?.let { MaterialDefinition(it.id, it.name) }

}

data class WeaponDefinition(
    val key: String,
    val name: String,
    val rarity: Int = 0,
    val weaponType: String? = null,
    val secondaryStatType: FightProperty? = null,
    val baseAttack: Double? = null,
    val baseSecondaryStat: Double? = null,
    val description: String? = null,
    val region: String? = null,
    val obtainMethod: String? = null,
    val releaseVersion: String? = null,
    val passiveName: String? = null,
    val passiveDescription: String? = null,
    val story: String? = null,
    val imageUrls: Map<WeaponImageType, String> = emptyMap(),
    val remoteImageUrls: Map<WeaponImageType, String> = emptyMap(),
    val hoyolabEntryId: Long? = null,
    val hoyolabPageVersion: String? = null,
    val hoyolabDataVersion: Int = 0,
    val hoyolabAscension: List<WeaponHoyolabAscension> = emptyList(),
    val ascensionCosts: Map<Int, List<MaterialCost>> = emptyMap(),
) {
    val hasDetails: Boolean
        get() = rarity in 1..5

    fun imageUrl(type: WeaponImageType): String? = imageUrls[type]

    fun remoteImageUrl(type: WeaponImageType): String? = remoteImageUrls[type]
}

data class WeaponHoyolabAscension(
    val level: Int,
    val attackBeforeAscension: Double? = null,
    val attackAfterAscension: Double? = null,
    val secondaryStat: Double? = null,
)
