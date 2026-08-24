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

interface GenshinStaticDataSource {
    fun fetchDataset(dataset: GenshinStaticDataset): List<JsonNode>
}

enum class GenshinStaticDataset(val key: String) {
    ARTIFACTS("artifacts"),
    CHARACTERS("characters"),
    CONSTELLATIONS("constellations"),
    DOMAINS("domains"),
    ELEMENTS("elements"),
    MATERIALS("materials"),
    TALENTS("talents"),
    WEAPONS("weapons"),
    ;

    companion object {
        fun fromKey(value: String): GenshinStaticDataset? = entries.find {
            it.key == value.trim().lowercase()
        }
    }
}

/** Narrow on-demand access to the normalized records still needed for game-id calculations. */
interface GenshinDetailDataSource {
    fun fetchCharacter(query: String): JsonNode?

    fun fetchTalents(query: String): JsonNode?

    fun fetchWeapon(query: String): JsonNode?
}

@Service
class GenshinDbApiClient(
    private val objectMapper: ObjectMapper,
    private val properties: GenshinContentProperties,
) : GenshinStaticDataSource, GenshinDetailDataSource {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun fetchDataset(dataset: GenshinStaticDataset): List<JsonNode> {
        val normalizedFolder = dataset.key
        val baseUrl = properties.characterApiUrl.trimEnd('/')
        val uri = URI.create(
            "$baseUrl/$normalizedFolder" +
                "?query=names&matchCategories=true" +
                "&verboseCategories=true&resultLanguage=English",
        )
        val root = fetchJson(uri, "folder '$normalizedFolder'")
        if (!root.isArray || root.isEmpty || root.any { !it.isObject }) {
            throw GenshinDbApiException(
                "genshin-db response for '$normalizedFolder' was not a non-empty object array",
            )
        }
        return root.toList()
    }

    fun fetchFolder(folder: String): List<JsonNode> = fetchDataset(
        GenshinStaticDataset.fromKey(folder)
            ?: throw IllegalArgumentException("Unsupported genshin-db folder '$folder'"),
    )

    override fun fetchCharacter(query: String): JsonNode? = fetchObject("characters", query)

    override fun fetchTalents(query: String): JsonNode? = fetchObject("talents", query)

    override fun fetchWeapon(query: String): JsonNode? = fetchObject("weapons", query)

    private fun fetchObject(category: String, query: String): JsonNode? {
        val normalizedQuery = query.trim().takeIf(String::isNotBlank) ?: return null
        val encodedQuery = URLEncoder.encode(normalizedQuery, StandardCharsets.UTF_8)
        val baseUrl = properties.characterApiUrl.trimEnd('/')
        val root = fetchJson(
            URI.create("$baseUrl/$category?query=$encodedQuery"),
            "$category query '$normalizedQuery'",
        )
        if (!root.isObject) {
            throw GenshinDbApiException(
                "genshin-db $category query '$normalizedQuery' did not return an object",
            )
        }
        return root
    }

    private fun fetchJson(uri: URI, description: String): JsonNode {
        val request = HttpRequest.newBuilder(uri)
            .timeout(properties.requestTimeout)
            .header("Accept", "application/json")
            .header("User-Agent", "GenshinApp/${properties.staticImportUserAgentVersion}")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            throw GenshinDbApiException(
                "genshin-db returned HTTP ${response.statusCode()} for $description",
            )
        }
        if (response.body().isEmpty()) {
            throw GenshinDbApiException("genshin-db returned an empty response for $description")
        }
        if (response.body().size > MAX_RESPONSE_BYTES) {
            throw GenshinDbApiException(
                "genshin-db response for $description exceeded $MAX_RESPONSE_BYTES bytes",
            )
        }
        return runCatching { objectMapper.readTree(response.body()) }
            .getOrElse { error ->
                throw GenshinDbApiException(
                    "genshin-db response for $description was not valid JSON",
                    error,
                )
            }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 64 * 1024 * 1024
    }
}

class GenshinDbApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
