package de.tyro.genshinapp.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

interface GenshinStaticDataSource {
    fun fetchFolder(folder: String): List<JsonNode>
}

@Service
class GenshinDbApiClient(
    private val objectMapper: ObjectMapper,
    private val properties: GenshinContentProperties,
) : GenshinStaticDataSource {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun fetchFolder(folder: String): List<JsonNode> {
        val normalizedFolder = folder.trim().lowercase()
        require(normalizedFolder.matches(FOLDER_PATTERN)) {
            "Invalid genshin-db folder '$folder'"
        }
        val baseUrl = properties.characterApiUrl.trimEnd('/')
        val uri = URI.create(
            "$baseUrl/$normalizedFolder" +
                "?query=names&matchCategories=true" +
                "&verboseCategories=true&resultLanguage=English",
        )
        val request = HttpRequest.newBuilder(uri)
            .timeout(properties.requestTimeout)
            .header("Accept", "application/json")
            .header("User-Agent", "GenshinApp/${properties.staticImportUserAgentVersion}")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            throw GenshinDbApiException(
                "genshin-db returned HTTP ${response.statusCode()} for '$normalizedFolder'",
            )
        }
        if (response.body().isEmpty()) {
            throw GenshinDbApiException(
                "genshin-db returned an empty response for '$normalizedFolder'",
            )
        }
        if (response.body().size > MAX_RESPONSE_BYTES) {
            throw GenshinDbApiException(
                "genshin-db response for '$normalizedFolder' exceeded $MAX_RESPONSE_BYTES bytes",
            )
        }

        val root = runCatching { objectMapper.readTree(response.body()) }
            .getOrElse { error ->
                throw GenshinDbApiException(
                    "genshin-db response for '$normalizedFolder' was not valid JSON",
                    error,
                )
            }
        if (!root.isArray || root.isEmpty || root.any { !it.isObject }) {
            throw GenshinDbApiException(
                "genshin-db response for '$normalizedFolder' was not a non-empty object array",
            )
        }
        return root.toList()
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 64 * 1024 * 1024
        private val FOLDER_PATTERN = Regex("[a-z0-9]+")
    }
}

class GenshinDbApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
