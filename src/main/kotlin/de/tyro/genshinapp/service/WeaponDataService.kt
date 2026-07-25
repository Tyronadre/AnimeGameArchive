package de.tyro.genshinapp.service

import com.fasterxml.jackson.databind.JsonNode
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.MaterialCost
import de.tyro.genshinapp.model.MaterialDefinition
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class WeaponDataService(
    private val contentLoader: DynamicContentLoader,
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
        if (stored?.hasDetails == true && !stored.weaponType.isNullOrBlank()) {
            definitions[normalizedKey] = stored
            return stored
        }
        val refreshed = contentLoader.loadWeaponJson(normalizedKey)
            ?.let { mapDefinition(normalizedKey, it) }
            ?.let { loaded ->
                loaded.copy(
                    imageUrl = stored?.imageUrl,
                    remoteImageUrl = stored?.remoteImageUrl,
                    hoyolabEntryId = stored?.hoyolabEntryId,
                    fullImageUrl = stored?.fullImageUrl,
                    galleryImages = stored?.galleryImages.orEmpty(),
                )
            }
            ?.let { loaded -> catalogStore?.saveWeapon(loaded) ?: loaded }
            ?.also {
                definitions[normalizedKey] = it
                weaponCatalogService?.rememberPersisted(it)
            }
        if (refreshed != null) return refreshed

        return stored?.also { definitions.putIfAbsent(normalizedKey, it) }
    }

    fun findKnownMaterial(id: Int): MaterialDefinition? =
        (definitions.values + catalogStore?.getWeapons().orEmpty()).asSequence()
            .flatMap { it.ascensionCosts.values.flatten().asSequence() }
            .find { it.id == id }
            ?.let { MaterialDefinition(it.id, it.name) }

    private fun mapDefinition(key: String, root: JsonNode): WeaponDefinition =
        WeaponDefinition(
            key = key,
            name = root.path("name").asText(GoodKeyNormalizer.humanize(key)),
            rarity = root.path("rarity").asInt(1).coerceIn(1, 5),
            weaponType = root.optionalText("weaponText"),
            secondaryStatType = root.path("mainStatType").asText()
                .takeIf(String::isNotBlank),
            baseAttack = root.path("baseAtkValue").takeIf(JsonNode::isNumber)?.asDouble(),
            baseSecondaryStat = parseSecondaryStat(root.path("baseStatText").asText()),
            description = root.optionalText("description"),
            passiveName = root.optionalText("effectName"),
            passiveDescription = root.optionalText("effectTemplateRaw")
                ?: root.optionalText("effectTemplate"),
            ascensionCosts = root.path("costs").properties()
                .mapNotNull { (phaseKey, costs) ->
                    val phase = phaseKey.removePrefix("ascend").toIntOrNull()
                        ?: return@mapNotNull null
                    phase to costs.map { cost ->
                        MaterialCost(
                            id = cost.path("id").asInt(),
                            name = cost.path("name").asText(),
                            count = cost.path("count").asLong(),
                        )
                    }
                }
                .sortedBy(Pair<Int, List<MaterialCost>>::first)
                .toMap(),
        )

    private fun parseSecondaryStat(value: String): Double? =
        value.trim()
            .removeSuffix("%")
            .toDoubleOrNull()

    private fun JsonNode.optionalText(field: String): String? =
        path(field).takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
}

data class WeaponDefinition(
    val key: String,
    val name: String,
    val rarity: Int = 0,
    val weaponType: String? = null,
    val secondaryStatType: String? = null,
    val baseAttack: Double? = null,
    val baseSecondaryStat: Double? = null,
    val description: String? = null,
    val passiveName: String? = null,
    val passiveDescription: String? = null,
    val imageUrl: String? = null,
    val remoteImageUrl: String? = null,
    val hoyolabEntryId: Long? = null,
    val fullImageUrl: String? = null,
    val galleryImages: List<WeaponGalleryImage> = emptyList(),
    val ascensionCosts: Map<Int, List<MaterialCost>> = emptyMap(),
) {
    val hasDetails: Boolean
        get() = rarity in 1..5
}

data class WeaponGalleryImage(
    val label: String,
    val url: String,
    val description: String? = null,
)
