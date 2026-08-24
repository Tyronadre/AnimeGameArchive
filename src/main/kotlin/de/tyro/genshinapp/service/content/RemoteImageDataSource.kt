package de.tyro.genshinapp.service.content

import de.tyro.genshinapp.configuration.GenshinContentProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

interface RemoteImageDataSource {
    fun fetchImage(uri: URI): RemoteImage?
}

data class RemoteImage(
    val bytes: ByteArray,
    val contentType: String,
)

@Service
class HttpRemoteImageDataSource(
    private val properties: GenshinContentProperties,
) : RemoteImageDataSource {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun fetchImage(uri: URI): RemoteImage? {
        val request = HttpRequest.newBuilder(uri)
            .timeout(properties.requestTimeout)
            .header("Accept", "image/*,*/*;q=0.1")
            .header("User-Agent", "GenshinApp/1.0")
            .GET()
            .build()
        val response = runCatching {
            httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        }.onFailure {
            logger.warn("Could not download image {}", uri, it)
        }.getOrNull() ?: return null
        val bytes = response.body()
        if (response.statusCode() !in 200..299 || bytes.isEmpty() || bytes.size > MAX_IMAGE_SIZE) {
            logger.warn("Download of image {} failed with HTTP {}", uri, response.statusCode())
            return null
        }
        val headerContentType = response.headers()
            .firstValue("Content-Type")
            .orElse("")
            .substringBefore(';')
            .trim()
        val contentType = headerContentType.takeIf { it.startsWith("image/") }
            ?: detectImageContentType(bytes)
            ?: return null
        return RemoteImage(bytes, contentType)
    }

    private companion object {
        private const val MAX_IMAGE_SIZE = 20 * 1024 * 1024
    }
}

internal fun detectImageContentType(bytes: ByteArray): String? = when {
    bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() &&
        bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() &&
        bytes[3] == 0x47.toByte() -> "image/png"

    bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte() -> "image/jpeg"

    bytes.size >= 12 &&
        String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WEBP" -> "image/webp"

    bytes.size >= 6 &&
        String(bytes, 0, 6, StandardCharsets.US_ASCII) in setOf("GIF87a", "GIF89a") ->
        "image/gif"

    else -> null
}
