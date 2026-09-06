package de.tyro.genshinapp.service.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialDefinition
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Stable bridge between HoYoWiki page ids and the game item ids used by GOOD/inventory data.
 *
 * HoYoWiki material references only contain an `ep_id`. That value identifies a wiki page, not
 * an in-game item. The source-controlled JSON catalog supplies the stable game id by material
 * name. A validated remote copy can update the bridge without replacing the desktop app.
 */
@Service
class MaterialIdentityCatalog(
    private val objectMapper: ObjectMapper,
    private val properties: GenshinContentProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val cacheFile = Path.of(properties.cacheDirectory)
        .toAbsolutePath()
        .normalize()
        .resolve("material-identities.json")

    @Volatile
    private var catalog = loadInitialCatalog()

    fun materials(): List<MaterialDefinition> = catalog.materials.map(MaterialIdentity::definition)

    fun gameIdForName(name: String): Int? = catalog.byName[GoodKeyNormalizer.normalize(name)]?.gameId

    /** Returns true when a newer valid remote document replaced the active catalog. */
    @Synchronized
    fun refreshRemote(): Boolean {
        val configuredUrl = properties.materialIdentityCatalogUrl.trim()
        if (configuredUrl.isBlank()) return false
        return runCatching {
            val request = HttpRequest.newBuilder(URI.create(configuredUrl))
                .timeout(properties.requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", "GenshinApp/material-catalog")
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            require(response.statusCode() in 200..299) {
                "HTTP ${response.statusCode()}"
            }
            require(response.body().size <= MAX_CATALOG_BYTES) {
                "response exceeded $MAX_CATALOG_BYTES bytes"
            }
            val refreshed = parse(response.body())
            if (refreshed.catalogVersion <= catalog.catalogVersion) return false
            writeCache(response.body())
            catalog = refreshed
            logger.info(
                "Updated material identity catalog to revision {} with {} entries",
                refreshed.catalogVersion,
                refreshed.materials.size,
            )
            true
        }.onFailure { error ->
            logger.warn(
                "Could not update the material identity catalog; keeping bundled/cached data: {}",
                error.message,
            )
        }.getOrDefault(false)
    }

    private fun loadInitialCatalog(): LoadedMaterialIdentityCatalog {
        val bundled = ClassPathResource(BUNDLED_RESOURCE).inputStream.use { parse(it.readAllBytes()) }
        if (!Files.isRegularFile(cacheFile)) return bundled
        val cached = runCatching { parse(Files.readAllBytes(cacheFile)) }
            .onFailure { logger.warn("Ignoring invalid cached material identity catalog", it) }
            .getOrNull()
        return cached?.takeIf { it.catalogVersion > bundled.catalogVersion } ?: bundled
    }

    private fun parse(bytes: ByteArray): LoadedMaterialIdentityCatalog {
        require(bytes.isNotEmpty() && bytes.size <= MAX_CATALOG_BYTES) {
            "Material identity catalog has an invalid size"
        }
        val document = objectMapper.readValue(bytes, MaterialIdentityDocument::class.java)
        require(document.formatVersion == FORMAT_VERSION) {
            "Unsupported material identity catalog format ${document.formatVersion}"
        }
        require(document.catalogVersion > 0) { "Material identity catalog has no revision" }
        require(document.materials.isNotEmpty()) { "Material identity catalog is empty" }

        val byId = linkedMapOf<Int, MaterialIdentity>()
        val byName = linkedMapOf<String, MaterialIdentity>()
        document.materials.forEach { material ->
            require(material.gameId >= 0) { "Invalid material game id ${material.gameId}" }
            require(material.name.isNotBlank()) { "Material ${material.gameId} has no name" }
            require(byId.put(material.gameId, material) == null) {
                "Duplicate material game id ${material.gameId}"
            }
            val normalizedName = GoodKeyNormalizer.normalize(material.name)
            require(normalizedName.isNotBlank() && byName.put(normalizedName, material) == null) {
                "Duplicate material name '${material.name}'"
            }
        }
        return LoadedMaterialIdentityCatalog(document.catalogVersion, byId.values.toList(), byName)
    }

    private fun writeCache(bytes: ByteArray) {
        Files.createDirectories(cacheFile.parent)
        val temporary = Files.createTempFile(cacheFile.parent, "material-identities", ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(
                    temporary,
                    cacheFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private data class LoadedMaterialIdentityCatalog(
        val catalogVersion: Int,
        val materials: List<MaterialIdentity>,
        val byName: Map<String, MaterialIdentity>,
    )

    private companion object {
        private const val BUNDLED_RESOURCE = "data/material-identities.json"
        private const val FORMAT_VERSION = 1
        private const val MAX_CATALOG_BYTES = 2 * 1024 * 1024
    }
}

data class MaterialIdentityDocument(
    val formatVersion: Int = 0,
    val catalogVersion: Int = 0,
    val materials: List<MaterialIdentity> = emptyList(),
)

data class MaterialIdentity(
    val gameId: Int = -1,
    val name: String = "",
    val category: MaterialCategory = MaterialCategory.OTHER,
    val craftingFamily: String? = null,
    val craftingTier: Int? = null,
    val conversionGroup: String? = null,
) {
    fun definition() = MaterialDefinition(
        id = gameId,
        name = name,
        category = category,
        craftingFamily = craftingFamily,
        craftingTier = craftingTier,
        conversionGroup = conversionGroup,
    )
}
