package de.tyro.genshinapp.service.importer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.*
import org.jsoup.Jsoup
import org.springframework.stereotype.Component

data class ParsedEnemySource(
    val pageId: Long, val name: String, val type: MaterialSourceType,
    val region: String?, val drops: List<HoyolabMaterialReference>,
)

data class ParsedTalentChallenge(
    val pageId: Long, val materialName: String, val name: String, val region: String?,
    val schedule: MaterialSchedule?, val family: String, val tier: Int,
)

@Component
class HoyolabSourceParser(mapper: ObjectMapper) {
    private val parser = HoyolabPageParser(mapper)

    fun enemy(page: JsonNode): ParsedEnemySource {
        require(page.path("menu_id").asText() == "7") { "Not an enemy page" }
        val base = parser.baseValues(page)
        val types = parser.componentData(page, "baseInfo")?.path("list")
            ?.firstOrNull { it.path("key").asText() == "Type" }?.path("value")
            ?.flatMap { value ->
                val html = Jsoup.parse(value.asText())
                html.select("p,li").eachText().ifEmpty { html.wholeText().lines() }
            }?.map { it.trim().lowercase() }?.toSet().orEmpty()
        val type = when {
            "weekly boss" in types -> MaterialSourceType.WEEKLY_BOSS
            "boss" in types -> MaterialSourceType.WORLD_BOSS
            types.any { it in setOf("common enemy", "elite enemy", "local legend") } -> MaterialSourceType.ENEMY
            else -> throw UnresolvedSourceException("Unknown enemy type: ${base["Type"]}")
        }
        val loot = parser.componentData(page, "drop_material")
            ?: throw IllegalArgumentException("Missing drop_material component")
        require(loot.path("list").isArray) { "Malformed drop_material list" }
        val drops = loot.path("list").flatMap { value ->
            parser.materialReferences(value).also { require(it.isNotEmpty()) { "Malformed drop reference" } }
        }.distinctBy { it.hoyolabPageId }
        return ParsedEnemySource(pageId(page), page.path("name").asText().also { require(it.isNotBlank()) },
            type, base["Region"]?.takeUnless { it.isBlank() || it == "-" }, drops)
    }

    fun talent(page: JsonNode): ParsedTalentChallenge {
        require(page.path("menu_id").asText() == "9") { "Not a material page" }
        val name = page.path("name").asText()
        val tier = PREFIXES.indexOfFirst { name.startsWith(it) }
        if (tier < 0) throw UnresolvedSourceException("Unknown talent family: $name")
        val base = parser.baseValues(page)
        val match = CHALLENGE.find(base["Source"].orEmpty())
            ?: throw UnresolvedSourceException("Missing Domain of Mastery source for $name")
        val days = match.groupValues[2].split('/').map { it.trim().lowercase() }.toSet()
        val schedule = when (days) {
            setOf("monday", "thursday", "sunday") -> MaterialSchedule.MONDAY_THURSDAY
            setOf("tuesday", "friday", "sunday") -> MaterialSchedule.TUESDAY_FRIDAY
            setOf("wednesday", "saturday", "sunday") -> MaterialSchedule.WEDNESDAY_SATURDAY
            else -> null
        }
        return ParsedTalentChallenge(pageId(page), name, match.groupValues[1].trim(),
            base["Region"]?.takeUnless { it.isBlank() || it == "-" }, schedule,
            "talent:${GoodKeyNormalizer.normalize(name.removePrefix(PREFIXES[tier]))}", tier)
    }

    fun materialType(page: JsonNode): String = parser.baseValues(page)["Type"].orEmpty().trim()

    companion object {
        const val VERSION = 1
        val PREFIXES = listOf("Teachings of ", "Guide to ", "Philosophies of ")
        fun isTalent(entry: HoyolabWikiEntry) = PREFIXES.any(entry.name::startsWith) ||
            entry.filters.values.flatten().any { it == "Character Talent Material" }
        private val CHALLENGE = Regex("Domain of Mastery:\\s*([^\\n(]+?)\\s*\\(([^)]+)\\)")
        fun pageId(page: JsonNode): Long = page.path("id").asLong().also { require(it > 0) }
    }
}

class UnresolvedSourceException(message: String) : IllegalArgumentException(message)
