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
) {
    private val definitions = ConcurrentHashMap<String, WeaponDefinition>()

    fun find(key: String): WeaponDefinition? {
        val normalizedKey = GoodKeyNormalizer.normalize(key)
        definitions[normalizedKey]?.let { return it }
        return contentLoader.loadWeaponJson(normalizedKey)
            ?.let { mapDefinition(normalizedKey, it) }
            ?.also { definitions.putIfAbsent(normalizedKey, it) }
    }

    fun findKnownMaterial(id: Int): MaterialDefinition? =
        definitions.values.asSequence()
            .flatMap { it.ascensionCosts.values.flatten().asSequence() }
            .find { it.id == id }
            ?.let { MaterialDefinition(it.id, it.name) }

    private fun mapDefinition(key: String, root: JsonNode): WeaponDefinition =
        WeaponDefinition(
            key = key,
            name = root.path("name").asText(GoodKeyNormalizer.humanize(key)),
            rarity = root.path("rarity").asInt(1).coerceIn(1, 5),
            secondaryStatType = root.path("mainStatType").asText()
                .takeIf(String::isNotBlank),
            baseSecondaryStat = parseSecondaryStat(root.path("baseStatText").asText()),
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
}

data class WeaponDefinition(
    val key: String,
    val name: String,
    val rarity: Int,
    val secondaryStatType: String? = null,
    val baseSecondaryStat: Double? = null,
    val ascensionCosts: Map<Int, List<MaterialCost>>,
)
