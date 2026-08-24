package de.tyro.genshinapp.service.importer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/** The stable application boundary around HoYoWiki's HTTP API. */
interface HoyolabWikiDataSource {
    fun listEntries(menu: HoyolabWikiMenu): List<HoyolabWikiEntry>

    fun search(keyword: String): List<HoyolabWikiSearchHit>

    fun fetchPage(entryPageId: Long): JsonNode?
}

enum class HoyolabWikiMenu(val id: String) {
    CHARACTERS("2"),
    WEAPONS("4"),
    ARTIFACTS("5"),
    ENEMIES("7"),
    MATERIALS("9"),
}

data class HoyolabWikiEntry(
    val entryPageId: Long,
    val name: String,
    val iconUrl: String? = null,
    val description: String? = null,
    val filters: Map<String, List<String>> = emptyMap(),
    val displayFields: Map<String, String> = emptyMap(),
) {
    fun firstFilter(key: String): String? = filters[key]?.firstOrNull()
}

data class HoyolabWikiSearchHit(
    val entryPageId: Long,
    val name: String,
    val menuIds: Set<String> = emptySet(),
)

@Service
class HttpHoyolabWikiDataSource(
    private val objectMapper: ObjectMapper,
    private val properties: GenshinContentProperties,
) : HoyolabWikiDataSource {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun listEntries(menu: HoyolabWikiMenu): List<HoyolabWikiEntry> {
        val entries = linkedMapOf<Long, HoyolabWikiEntry>()
        var fetchedCount = 0
        var totalCount = Int.MAX_VALUE

        for (pageNumber in 1..MAX_PAGES) {
            val data = post(
                "/get_entry_page_list",
                mapOf(
                    "filters" to emptyList<String>(),
                    "menu_id" to menu.id,
                    "page_num" to pageNumber,
                    "page_size" to PAGE_SIZE,
                    "use_es" to true,
                ),
            ).path("data")
            val page = data.path("list").takeIf(JsonNode::isArray)?.toList().orEmpty()
            if (page.isEmpty()) break

            fetchedCount += page.size
            totalCount = data.path("total").asText().toIntOrNull() ?: totalCount
            page.mapNotNull(::toEntry).forEach { entry -> entries[entry.entryPageId] = entry }
            if (fetchedCount >= totalCount) break
        }
        return entries.values.toList()
    }

    override fun search(keyword: String): List<HoyolabWikiSearchHit> {
        val normalizedKeyword = keyword.trim().takeIf(String::isNotBlank) ?: return emptyList()
        val encodedKeyword = URLEncoder.encode(normalizedKeyword, StandardCharsets.UTF_8)
        return get("/search?keyword=$encodedKeyword").descendants()
            .filter { node -> node.isObject && entryId(node) != null }
            .mapNotNull { node ->
                val name = node.path("name").asText().trim().takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                HoyolabWikiSearchHit(
                    entryPageId = entryId(node) ?: return@mapNotNull null,
                    name = name,
                    menuIds = buildSet {
                        node.path("menu_id").asText().takeIf(String::isNotBlank)?.let(::add)
                        node.path("menu").descendants()
                            .map { child -> child.path("id").asText() }
                            .filter(String::isNotBlank)
                            .forEach(::add)
                    },
                )
            }
            .distinctBy(HoyolabWikiSearchHit::entryPageId)
            .toList()
    }

    override fun fetchPage(entryPageId: Long): JsonNode? {
        require(entryPageId > 0) { "HoYoWiki entry page id must be positive" }
        return get("/entry_page?entry_page_id=$entryPageId")
            .path("data")
            .path("page")
            .takeIf(JsonNode::isObject)
    }

    private fun toEntry(node: JsonNode): HoyolabWikiEntry? {
        val entryPageId = entryId(node) ?: return null
        val name = node.path("name").asText().trim().takeIf(String::isNotBlank) ?: return null
        return HoyolabWikiEntry(
            entryPageId = entryPageId,
            name = name,
            iconUrl = node.path("icon_url").asText().trim().takeIf(String::isNotBlank),
            description = node.path("desc").asText().trim().takeIf(String::isNotBlank),
            filters = node.path("filter_values").takeIf(JsonNode::isObject)
                ?.properties()
                ?.associate { (key, value) -> key to filterValues(value) }
                .orEmpty(),
            displayFields = node.path("display_field").takeIf(JsonNode::isObject)
                ?.properties()
                ?.mapNotNull { (key, value) ->
                    value.asText().trim().takeIf(String::isNotBlank)?.let { key to it }
                }
                ?.toMap()
                .orEmpty(),
        )
    }

    private fun filterValues(filter: JsonNode): List<String> {
        val displayValues = filter.path("values")
            .takeIf(JsonNode::isArray)
            ?.mapNotNull { it.asText().trim().takeIf(String::isNotBlank) }
            .orEmpty()
        if (displayValues.isNotEmpty()) return displayValues
        return filter.path("value_types")
            .takeIf(JsonNode::isArray)
            ?.mapNotNull { value ->
                value.path("enum_string").asText().trim().takeIf(String::isNotBlank)
                    ?: value.path("value").asText().trim().takeIf(String::isNotBlank)
            }
            .orEmpty()
    }

    private fun get(pathAndQuery: String): JsonNode {
        val request = requestBuilder(pathAndQuery).GET().build()
        return send(request)
    }

    private fun post(path: String, body: Any): JsonNode {
        val request = requestBuilder(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
            .build()
        return send(request)
    }

    private fun requestBuilder(pathAndQuery: String): HttpRequest.Builder {
        val baseUrl = properties.hoyolabWikiApiUrl.trimEnd('/')
        return HttpRequest.newBuilder(URI.create("$baseUrl$pathAndQuery"))
            .timeout(properties.requestTimeout)
            .header("Accept", "application/json")
            .header("Referer", "https://wiki.hoyolab.com/")
            .header("User-Agent", "Mozilla/5.0 (compatible; GenshinArchive/1.0)")
            .header("x-rpc-language", "en-us")
    }

    private fun send(request: HttpRequest): JsonNode {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299 || response.body().isEmpty()) {
            throw HoyolabWikiException(
                "HoYoWiki returned HTTP ${response.statusCode()} for ${request.uri().path}",
            )
        }
        if (response.body().size > MAX_RESPONSE_BYTES) {
            throw HoyolabWikiException("HoYoWiki response exceeded $MAX_RESPONSE_BYTES bytes")
        }
        val root = runCatching { objectMapper.readTree(response.body()) }
            .getOrElse { throw HoyolabWikiException("HoYoWiki returned invalid JSON", it) }
        if (root.path("retcode").asInt(-1) != 0) {
            throw HoyolabWikiException(
                "HoYoWiki returned retcode ${root.path("retcode").asInt(-1)}",
            )
        }
        return root
    }

    private fun entryId(node: JsonNode): Long? =
        node.path("entry_page_id").asText().toLongOrNull()
            ?: node.path("id").asText().toLongOrNull()

    private fun JsonNode.descendants(): Sequence<JsonNode> = sequence {
        yield(this@descendants)
        for (child in this@descendants) yieldAll(child.descendants())
    }

    private companion object {
        private const val PAGE_SIZE = 50
        private const val MAX_PAGES = 100
        private const val MAX_RESPONSE_BYTES = 32 * 1024 * 1024
    }
}

class HoyolabWikiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal fun isAllowedHoyolabImageUrl(value: String): Boolean {
    if (value.length !in 1..4096) return false
    val uri = runCatching { URI.create(value) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && uri.host?.lowercase() in setOf(
        "upload-static.hoyoverse.com",
        "act-upload.hoyoverse.com",
        "act-webstatic.hoyoverse.com",
        "bbs.hoyolab.com",
        "img-os-static.hoyolab.com",
        "upload-os-bbs.hoyolab.com",
    )
}
