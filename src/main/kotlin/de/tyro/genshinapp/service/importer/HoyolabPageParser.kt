package de.tyro.genshinapp.service.importer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.MaterialCost
import org.jsoup.Jsoup

/** Shared parsing primitives for the JSON strings embedded in HoYoWiki page components. */
class HoyolabPageParser(
    private val objectMapper: ObjectMapper,
) {
    fun componentData(page: JsonNode, componentId: String): JsonNode? {
        val component = page.descendants()
            .firstOrNull { it.path("component_id").asText() == componentId }
            ?: return null
        val data = component.path("data")
        return if (data.isTextual) {
            data.asText().takeIf(String::isNotBlank)?.let { raw ->
                runCatching { objectMapper.readTree(raw) }.getOrNull()
            }
        } else {
            data.takeUnless(JsonNode::isMissingNode)
        }
    }

    fun baseValues(page: JsonNode): Map<String, String> = componentData(page, "baseInfo")
        ?.path("list")
        ?.takeIf(JsonNode::isArray)
        ?.associate { item ->
            item.path("key").asText() to item.path("value").plainValues().joinToString("\n")
        }
        .orEmpty()

    fun materialReferences(node: JsonNode): List<HoyolabMaterialReference> = when {
        node.isArray -> node.flatMap(::materialReferences)
        node.isTextual -> embeddedArray(node.asText()).mapNotNull(::materialReference)
        node.isObject -> listOfNotNull(materialReference(node))
        else -> emptyList()
    }

    fun plainText(value: String?): String? = value
        ?.takeIf(String::isNotBlank)
        ?.let { Jsoup.parse(it).body().wholeText() }
        ?.replace(HORIZONTAL_WHITESPACE, " ")
        ?.replace(EXCESS_NEWLINES, "\n\n")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    fun number(value: String?): Double? = value
        ?.replace(",", "")
        ?.replace("%", "")
        ?.replace("％", "")
        ?.trim()
        ?.takeUnless { it == "-" }
        ?.toDoubleOrNull()

    private fun embeddedArray(raw: String): List<JsonNode> {
        val json = raw.trim().removePrefix("\$").removeSuffix("\$")
        return runCatching { objectMapper.readTree(json) }
            .getOrNull()
            ?.takeIf(JsonNode::isArray)
            ?.toList()
            .orEmpty()
    }

    private fun materialReference(node: JsonNode): HoyolabMaterialReference? {
        val id = node.path("ep_id").asInt().takeIf { it > 0 } ?: return null
        val name = sequenceOf("name", "nickname")
            .map { field -> node.path(field).asText().trim() }
            .firstOrNull(String::isNotBlank)
        val imageUrl = sequenceOf("icon", "img")
            .map { field -> node.path(field).asText().trim() }
            .mapNotNull(::normalizeHoyolabImageUrl)
            .firstOrNull()
        return HoyolabMaterialReference(
            id = id,
            hoyolabPageId = id,
            name = name,
            amount = node.path("amount").asLong(),
            imageUrl = imageUrl,
        )
    }

    private fun JsonNode.plainValues(): List<String> = takeIf(JsonNode::isArray)
        ?.mapNotNull { value -> plainText(value.asText()) }
        .orEmpty()

    private fun JsonNode.descendants(): Sequence<JsonNode> = sequence {
        yield(this@descendants)
        for (child in this@descendants) yieldAll(child.descendants())
    }

    private companion object {
        private val HORIZONTAL_WHITESPACE = Regex("[\\t \\x0B\\f\\r]+")
        private val EXCESS_NEWLINES = Regex("\\n{3,}")
    }
}

data class HoyolabMaterialReference(
    val id: Int,
    val name: String?,
    val amount: Long,
    val imageUrl: String? = null,
    val hoyolabPageId: Int = id,
) {
    fun toCost(): MaterialCost = MaterialCost(
        id,
        requireNotNull(name) { "HoYoWiki material $hoyolabPageId has not been resolved" },
        amount,
    )
}
