package de.tyro.genshinapp.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.GoodKeyNormalizer
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

@Service
class HoyolabWeaponGalleryService(
    private val objectMapper: ObjectMapper,
    private val properties: GenshinContentProperties,
    private val weaponCatalogService: WeaponCatalogService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val lookupLocks = ConcurrentHashMap<String, Any>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * Lazily discovers and stores HoYoWiki gallery metadata. Existing database values are used
     * without contacting HoYoWiki, and transient failures are retried after a short cooldown.
     */
    fun enrich(key: String): WeaponDefinition? {
        val normalizedKey = GoodKeyNormalizer.normalize(key)
        val existing = weaponCatalogService.find(normalizedKey) ?: return null
        if (existing.hasHoyolabGallery || !properties.hoyolabWikiEnabled) return existing
        val lastFailure = failedAt[normalizedKey]
        if (lastFailure != null && System.currentTimeMillis() - lastFailure < FAILURE_COOLDOWN_MS) {
            return existing
        }

        return synchronized(lookupLocks.computeIfAbsent(normalizedKey) { Any() }) {
            val current = weaponCatalogService.find(normalizedKey) ?: return@synchronized null
            if (current.hasHoyolabGallery) return@synchronized current

            val gallery = runCatching { lookupGallery(current) }
                .onFailure {
                    logger.warn("Could not load the HoYoWiki gallery for {}", current.name, it)
                }
                .getOrNull()
            if (gallery == null) {
                failedAt[normalizedKey] = System.currentTimeMillis()
                return@synchronized current
            }

            failedAt.remove(normalizedKey)
            weaponCatalogService.saveEnrichment(
                current.copy(
                    hoyolabEntryId = gallery.entryId,
                    fullImageUrl = gallery.preferred.url,
                    galleryImages = gallery.images,
                ),
            )
        }
    }

    private fun lookupGallery(weapon: WeaponDefinition): GalleryResult? {
        val entryIds = weapon.hoyolabEntryId?.let(::listOf)
            ?: findWeaponEntryIds(weapon.name)
        return entryIds.firstNotNullOfOrNull(::loadGallery)
    }

    private fun loadGallery(entryId: Long): GalleryResult? {
        val root = getJson("/entry_page?entry_page_id=$entryId") ?: return null
        val galleryComponent = root.descendants()
            .firstOrNull { it.path("component_id").asText() == GALLERY_COMPONENT_ID }
            ?: return null
        val galleryData = galleryComponent.path("data").takeIf(JsonNode::isTextual)
            ?.asText()
            ?.let(objectMapper::readTree)
            ?: galleryComponent.path("data")
        val images = galleryData.path("list")
            .takeIf(JsonNode::isArray)
            ?.mapNotNull(::toGalleryImage)
            .orEmpty()
        if (images.isEmpty()) return null

        val preferred = images.firstOrNull { it.label.equals("Awakened", ignoreCase = true) }
            ?: images.firstOrNull {
                it.description?.contains("after", ignoreCase = true) == true
            }
            ?: images.last()
        return GalleryResult(entryId, images, preferred)
    }

    private fun findWeaponEntryIds(name: String): List<Long> {
        val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8)
        val root = getJson("/search?keyword=$encodedName") ?: return emptyList()
        return root.descendants()
            .filter { node ->
                node.isObject &&
                    node.path("name").asText().equals(name, ignoreCase = true) &&
                    entryId(node) != null
            }
            .sortedByDescending(::belongsToWeaponMenu)
            .mapNotNull(::entryId)
            .distinct()
            .toList()
    }

    private fun entryId(node: JsonNode): Long? =
        node.path("entry_page_id").asText().toLongOrNull()
            ?: node.path("id").asText().toLongOrNull()

    private fun belongsToWeaponMenu(node: JsonNode): Boolean {
        if (node.path("menu_id").asText() == WEAPON_MENU_ID) return true
        val menu = node.path("menu")
        return menu.descendants().any { child -> child.path("id").asText() == WEAPON_MENU_ID }
    }

    private fun toGalleryImage(node: JsonNode): WeaponGalleryImage? {
        val url = node.path("img").asText().takeIf(::isAllowedImageUrl) ?: return null
        val label = node.path("key").asText().ifBlank { "Gallery" }
        val description = node.path("imgDesc").asText()
            .takeIf(String::isNotBlank)
            ?.let { Jsoup.parse(it).text().takeIf(String::isNotBlank) }
        return WeaponGalleryImage(label, url, description)
    }

    private fun getJson(pathAndQuery: String): JsonNode? {
        val baseUrl = properties.hoyolabWikiApiUrl.trimEnd('/')
        val uri = URI.create("$baseUrl$pathAndQuery")
        val request = HttpRequest.newBuilder(uri)
            .timeout(properties.requestTimeout)
            .header("Accept", "application/json")
            .header("Referer", "https://wiki.hoyolab.com/")
            .header("User-Agent", "Mozilla/5.0 (compatible; GenshinArchive/1.0)")
            .header("x-rpc-language", "en-us")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299 || response.body().isEmpty()) {
            logger.warn("HoYoWiki request {} returned HTTP {}", uri.path, response.statusCode())
            return null
        }
        val root = objectMapper.readTree(response.body())
        if (root.path("retcode").asInt(-1) != 0) {
            logger.warn("HoYoWiki request {} returned retcode {}", uri.path, root.path("retcode"))
            return null
        }
        return root
    }

    private fun isAllowedImageUrl(value: String): Boolean {
        if (value.length !in 1..MAX_URL_LENGTH) return false
        val uri = runCatching { URI.create(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() in ALLOWED_IMAGE_HOSTS
    }

    private fun JsonNode.descendants(): Sequence<JsonNode> = sequence {
        yield(this@descendants)
        for (child in this@descendants) {
            yieldAll(child.descendants())
        }
    }

    private val WeaponDefinition.hasHoyolabGallery: Boolean
        get() = !fullImageUrl.isNullOrBlank() && galleryImages.isNotEmpty()

    private data class GalleryResult(
        val entryId: Long,
        val images: List<WeaponGalleryImage>,
        val preferred: WeaponGalleryImage,
    )

    companion object {
        private const val WEAPON_MENU_ID = "4"
        private const val GALLERY_COMPONENT_ID = "gallery_character"
        private const val MAX_URL_LENGTH = 4096
        private const val FAILURE_COOLDOWN_MS = 5 * 60 * 1000L
        private val ALLOWED_IMAGE_HOSTS = setOf(
            "upload-static.hoyoverse.com",
            "act-upload.hoyoverse.com",
            "act-webstatic.hoyoverse.com",
        )
    }
}
