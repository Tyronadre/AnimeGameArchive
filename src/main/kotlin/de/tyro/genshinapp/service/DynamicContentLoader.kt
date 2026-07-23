package de.tyro.genshinapp.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterImageType
import de.tyro.genshinapp.model.CharacterTalent
import de.tyro.genshinapp.model.CharacterTalentKind
import de.tyro.genshinapp.model.MaterialDefinition
import de.tyro.genshinapp.model.TravelerElement
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

@Service
class DynamicContentLoader(
    private val objectMapper: ObjectMapper,
    private val properties: GenshinContentProperties,
    private val imageUrlRegistry: ImageUrlRegistry,
    private val fandomImageUrlResolver: FandomImageUrlResolver,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val cacheDirectory = Path.of(properties.cacheDirectory).toAbsolutePath().normalize()
    private val downloadLocks = ConcurrentHashMap<String, Any>()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun loadCharacterJson(key: String): JsonNode? {
        val normalizedKey = normalizeCharacterKey(key) ?: return null
        readClasspathJson("data/characters/data/$normalizedKey.json")?.let { return it }
        readCachedJson(characterDataPath(normalizedKey))?.let { return it }

        return synchronized(lockFor("character-data:$normalizedKey")) {
            readCachedJson(characterDataPath(normalizedKey))
                ?: downloadCharacterJson(normalizedKey)
        }
    }

    fun loadWeaponJson(key: String): JsonNode? {
        val normalizedKey = key.trim().lowercase()
            .takeIf { it.matches(ARTIFACT_KEY_PATTERN) }
            ?: return null
        readClasspathJson("data/weapons/data/$normalizedKey.json")?.let { return it }
        readCachedJson(weaponDataPath(normalizedKey))?.let { return it }

        return synchronized(lockFor("weapon-data:$normalizedKey")) {
            readCachedJson(weaponDataPath(normalizedKey))
                ?: downloadWeaponJson(normalizedKey)
        }
    }

    fun loadCharacterImage(
        character: CharacterDefinition,
        imageType: CharacterImageType,
    ): LoadedImage? {
        val remoteUrl = effectiveCharacterImageUrl(character, imageType) ?: return null
        return loadRemoteImage(characterImagePath(character.imageResourceKey, imageType), remoteUrl)
    }

    fun loadMaterialImage(id: Int, name: String): LoadedImage? {
        if (id < 0 || name.isBlank() || name.length > MAX_MATERIAL_NAME_LENGTH) return null

        val link = imageUrlRegistry.materialLink(id)
        val remoteUrl = link?.effectiveUrl ?: defaultMaterialImageUrl(name).takeIf { id > 0 }
        if (remoteUrl != null) {
            loadRemoteImage(materialImagePath(id), remoteUrl)?.let { return it }
        }

        val fileName = "${safeMaterialFileName(name)}.png"
        readClasspathImage("static/images/materials/$fileName", "image/png")?.let { return it }
        return null
    }

    fun loadArtifactImage(
        setKey: String,
        slotKey: String,
        pieceName: String,
    ): LoadedImage? {
        val normalizedSetKey = setKey.trim().lowercase()
            .takeIf { it.matches(ARTIFACT_KEY_PATTERN) }
            ?: return null
        val normalizedSlotKey = slotKey.trim().lowercase()
            .takeIf { it in ARTIFACT_SLOT_KEYS }
            ?: return null
        if (pieceName.isBlank() || pieceName.length > MAX_MATERIAL_NAME_LENGTH) return null

        return loadRemoteImage(
            artifactImagePath(normalizedSetKey, normalizedSlotKey),
            fandomImageUrlResolver.itemImageUrl(pieceName),
        )
    }

    fun loadWeaponImage(key: String, weaponName: String): LoadedImage? {
        val normalizedKey = key.trim().lowercase()
            .takeIf { it.matches(ARTIFACT_KEY_PATTERN) }
            ?: return null
        if (weaponName.isBlank() || weaponName.length > MAX_MATERIAL_NAME_LENGTH) return null

        val remoteUrl = imageUrlRegistry.weaponLink(normalizedKey)?.effectiveUrl
            ?: fandomImageUrlResolver.weaponImageUrl(weaponName)

        return loadRemoteImage(
            weaponImagePath(normalizedKey),
            remoteUrl,
        )
    }

    fun loadWeaponFullImage(
        key: String,
        weaponName: String,
        defaultUrl: String?,
    ): LoadedImage? {
        val normalizedKey = key.trim().lowercase()
            .takeIf { it.matches(ARTIFACT_KEY_PATTERN) }
            ?: return null
        if (weaponName.isBlank() || weaponName.length > MAX_MATERIAL_NAME_LENGTH) return null
        val remoteUrl = imageUrlRegistry.weaponFullLink(normalizedKey)?.effectiveUrl
            ?: defaultUrl?.takeIf(String::isNotBlank)
            ?: return null
        return loadRemoteImage(weaponFullImagePath(normalizedKey), remoteUrl)
    }

    fun loadTalentImage(
        characterKey: String,
        talentKey: String,
        talentName: String,
        normalAttackWeapon: String? = null,
        normalAttackElement: String? = null,
    ): LoadedImage? {
        val normalizedCharacterKey = normalizeCharacterKey(characterKey) ?: return null
        val normalizedTalentKey = talentKey.trim().lowercase()
            .takeIf { it.matches(ARTIFACT_KEY_PATTERN) }
            ?: return null
        if (talentName.isBlank() || talentName.length > MAX_MATERIAL_NAME_LENGTH) return null

        val defaultUrl = defaultTalentImageUrl(
            talentName,
            normalAttackWeapon,
            normalAttackElement,
        )
        val remoteUrl = imageUrlRegistry
            .talentLink(normalizedCharacterKey, normalizedTalentKey)
            ?.effectiveUrl
            ?: defaultUrl

        return loadRemoteImage(
            talentImagePath(normalizedCharacterKey, normalizedTalentKey),
            remoteUrl,
        )
    }

    fun registerDefaultImageLinks(
        characters: Collection<CharacterDefinition>,
        materials: Collection<MaterialDefinition>,
    ) {
        imageUrlRegistry.registerDefaults(
            characters = characters,
            materials = materials.map { material ->
                material to if (material.id > 0) defaultMaterialImageUrl(material.name) else ""
            },
            talents = characters.flatMap { character ->
                character.talents.map { talent ->
                    TalentImageDefault(
                        characterKey = character.talentResourceKey,
                        talentKey = talent.key,
                        name = "${character.name} - ${talent.name}",
                        defaultUrl = defaultTalentImageUrl(character, talent),
                    )
                }
            },
        )
    }

    fun updateCharacterImageUrl(
        character: CharacterDefinition,
        imageType: CharacterImageType,
        url: String,
    ): ImageUpdateResult {
        val validatedUrl = validateImageUrl(url) ?: return ImageUpdateResult(
            successful = false,
            messageKey = "images.update.invalidUrl",
        )
        val image = downloadImage(URI.create(validatedUrl)) ?: return ImageUpdateResult(
            successful = false,
            messageKey = "images.update.downloadFailed",
        )

        return runCatching {
            writeCachedImage(
                characterImagePath(character.imageResourceKey, imageType),
                image,
                validatedUrl,
            )
            imageUrlRegistry.setCharacterOverride(
                character.imageResourceKey,
                imageType,
                character.name,
                validatedUrl,
            )
            ImageUpdateResult(
                true,
                "images.update.characterSaved",
                arrayOf(imageType.label, character.name),
            )
        }.getOrElse {
            logger.error(
                "Could not save character image URL for {}",
                character.imageResourceKey,
                it,
            )
            ImageUpdateResult(false, "images.update.saveFailed")
        }
    }

    fun updateMaterialImageUrl(material: MaterialDefinition, url: String): ImageUpdateResult {
        val validatedUrl = validateImageUrl(url) ?: return ImageUpdateResult(
            successful = false,
            messageKey = "images.update.invalidUrl",
        )
        val image = downloadImage(URI.create(validatedUrl)) ?: return ImageUpdateResult(
            successful = false,
            messageKey = "images.update.downloadFailed",
        )

        return runCatching {
            writeCachedImage(materialImagePath(material.id), image, validatedUrl)
            imageUrlRegistry.setMaterialOverride(material.id, material.name, validatedUrl)
            ImageUpdateResult(
                true,
                "images.update.materialSaved",
                arrayOf(material.name),
            )
        }.getOrElse {
            logger.error("Could not save material image URL for {}", material.id, it)
            ImageUpdateResult(false, "images.update.saveFailed")
        }
    }

    fun updateWeaponImageUrl(key: String, name: String, url: String): ImageUpdateResult {
        val normalizedKey = key.trim().lowercase()
            .takeIf { it.matches(ARTIFACT_KEY_PATTERN) }
            ?: return ImageUpdateResult(false, "images.update.invalidUrl")
        val validatedUrl = validateImageUrl(url) ?: return ImageUpdateResult(
            successful = false,
            messageKey = "images.update.invalidUrl",
        )
        val image = downloadImage(URI.create(validatedUrl)) ?: return ImageUpdateResult(
            successful = false,
            messageKey = "images.update.downloadFailed",
        )

        return runCatching {
            writeCachedImage(weaponImagePath(normalizedKey), image, validatedUrl)
            imageUrlRegistry.setWeaponOverride(normalizedKey, name, validatedUrl)
            ImageUpdateResult(
                true,
                "images.update.weaponSaved",
                arrayOf(name),
            )
        }.getOrElse {
            logger.error("Could not save weapon image URL for {}", normalizedKey, it)
            ImageUpdateResult(false, "images.update.saveFailed")
        }
    }

    fun updateWeaponFullImageUrl(key: String, name: String, url: String): ImageUpdateResult {
        val normalizedKey = key.trim().lowercase()
            .takeIf { it.matches(ARTIFACT_KEY_PATTERN) }
            ?: return ImageUpdateResult(false, "images.update.invalidUrl")
        val validatedUrl = validateImageUrl(url) ?: return ImageUpdateResult(
            successful = false,
            messageKey = "images.update.invalidUrl",
        )
        val image = downloadImage(URI.create(validatedUrl)) ?: return ImageUpdateResult(
            successful = false,
            messageKey = "images.update.downloadFailed",
        )

        return runCatching {
            writeCachedImage(weaponFullImagePath(normalizedKey), image, validatedUrl)
            imageUrlRegistry.setWeaponFullOverride(normalizedKey, "$name full view", validatedUrl)
            ImageUpdateResult(
                true,
                "images.update.weaponFullSaved",
                arrayOf(name),
            )
        }.getOrElse {
            logger.error("Could not save full weapon image URL for {}", normalizedKey, it)
            ImageUpdateResult(false, "images.update.saveFailed")
        }
    }

    fun updateTalentImageUrl(
        character: CharacterDefinition,
        talent: CharacterTalent,
        url: String,
    ): ImageUpdateResult {
        val validatedUrl = validateImageUrl(url) ?: return ImageUpdateResult(
            successful = false,
            messageKey = "images.update.invalidUrl",
        )
        val image = downloadImage(URI.create(validatedUrl)) ?: return ImageUpdateResult(
            successful = false,
            messageKey = "images.update.downloadFailed",
        )

        return runCatching {
            writeCachedImage(
                talentImagePath(character.talentResourceKey, talent.key),
                image,
                validatedUrl,
            )
            imageUrlRegistry.setTalentOverride(
                character.talentResourceKey,
                talent.key,
                "${character.name} - ${talent.name}",
                validatedUrl,
            )
            ImageUpdateResult(
                true,
                "images.update.talentSaved",
                arrayOf(talent.name, character.name),
            )
        }.getOrElse {
            logger.error(
                "Could not save talent image URL for {}:{}",
                character.talentResourceKey,
                talent.key,
                it,
            )
            ImageUpdateResult(false, "images.update.saveFailed")
        }
    }

    fun resetCharacterImageUrl(
        character: CharacterDefinition,
        imageType: CharacterImageType,
    ) {
        imageUrlRegistry.resetCharacterOverride(character.imageResourceKey, imageType)
    }

    fun resetMaterialImageUrl(material: MaterialDefinition) {
        imageUrlRegistry.resetMaterialOverride(material.id)
    }

    fun resetTalentImageUrl(character: CharacterDefinition, talent: CharacterTalent) {
        imageUrlRegistry.resetTalentOverride(character.talentResourceKey, talent.key)
    }

    fun resetWeaponImageUrl(key: String) {
        imageUrlRegistry.resetWeaponOverride(key)
    }

    fun resetWeaponFullImageUrl(key: String) {
        imageUrlRegistry.resetWeaponFullOverride(key)
    }

    fun characterImageState(
        character: CharacterDefinition,
        imageType: CharacterImageType,
    ): ImageState {
        val effectiveUrl = effectiveCharacterImageUrl(character, imageType)
        return when {
            cachedImageMatches(
                characterImagePath(character.imageResourceKey, imageType),
                effectiveUrl,
            ) ->
                ImageState.CACHED
            effectiveUrl != null -> ImageState.REMOTE
            else -> ImageState.MISSING
        }
    }

    fun materialImageState(material: MaterialDefinition): ImageState {
        val link = imageUrlRegistry.materialLink(material.id)
        val effectiveUrl = link?.effectiveUrl
            ?: defaultMaterialImageUrl(material.name).takeIf { material.id > 0 }
        return when {
            cachedImageMatches(materialImagePath(material.id), effectiveUrl) -> ImageState.CACHED
            effectiveUrl != null -> ImageState.REMOTE
            ClassPathResource(
                "static/images/materials/${safeMaterialFileName(material.name)}.png",
            ).exists() -> ImageState.BUNDLED
            else -> ImageState.MISSING
        }
    }

    fun talentImageState(
        character: CharacterDefinition,
        talent: CharacterTalent,
    ): ImageState {
        val effectiveUrl = imageUrlRegistry
            .talentLink(character.talentResourceKey, talent.key)
            ?.effectiveUrl
            ?: defaultTalentImageUrl(character, talent)
        return when {
            cachedImageMatches(
                talentImagePath(character.talentResourceKey, talent.key),
                effectiveUrl,
            ) ->
                ImageState.CACHED
            effectiveUrl.isNotBlank() -> ImageState.REMOTE
            else -> ImageState.MISSING
        }
    }

    fun weaponImageState(key: String, name: String): ImageState {
        val normalizedKey = key.trim().lowercase()
        val effectiveUrl = imageUrlRegistry.weaponLink(normalizedKey)?.effectiveUrl
            ?: fandomImageUrlResolver.weaponImageUrl(name)
        return when {
            cachedImageMatches(weaponImagePath(normalizedKey), effectiveUrl) -> ImageState.CACHED
            effectiveUrl.isNotBlank() -> ImageState.REMOTE
            else -> ImageState.MISSING
        }
    }

    fun weaponFullImageState(key: String, defaultUrl: String?): ImageState {
        val normalizedKey = key.trim().lowercase()
        val effectiveUrl = imageUrlRegistry.weaponFullLink(normalizedKey)?.effectiveUrl
            ?: defaultUrl?.takeIf(String::isNotBlank)
        return when {
            cachedImageMatches(weaponFullImagePath(normalizedKey), effectiveUrl) -> ImageState.CACHED
            effectiveUrl != null -> ImageState.REMOTE
            else -> ImageState.MISSING
        }
    }

    private fun downloadCharacterJson(key: String): JsonNode? {
        val baseUrl = properties.characterApiUrl.trimEnd('/')
        val travelerElement = TravelerElement.fromKey(key)
            ?.takeIf { it.variantKey == key }
        if (travelerElement != null) {
            val encodedQuery = URLEncoder.encode(travelerElement.queryName, StandardCharsets.UTF_8)
            val talents = downloadJson(URI.create("$baseUrl/talents?query=$encodedQuery"))
                ?.takeIf(JsonNode::isObject)
                ?: return null
            val character = (readClasspathJson("data/characters/data/aether.json") as? ObjectNode)
                ?.deepCopy()
                ?: return null
            character.put("name", travelerElement.queryName)
            character.put("elementText", travelerElement.displayName)
            character.set<JsonNode>("talents", talents)
            writeCachedJson(characterDataPath(key), character)
            return character
        }

        val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8)
        val character = downloadJson(URI.create("$baseUrl/characters?query=$encodedKey"))
            ?.takeIf(::validCharacterJson)
            ?: return null
        val talents = downloadJson(URI.create("$baseUrl/talents?query=$encodedKey"))

        if (character is ObjectNode && talents?.isObject == true) {
            character.set<JsonNode>("talents", talents)
        }

        writeCachedJson(characterDataPath(key), character)
        return character
    }

    private fun downloadWeaponJson(key: String): JsonNode? {
        val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8)
        val baseUrl = properties.characterApiUrl.trimEnd('/')
        val weapon = downloadJson(URI.create("$baseUrl/weapons?query=$encodedKey"))
            ?.takeIf(::validWeaponJson)
            ?: return null
        writeCachedJson(weaponDataPath(key), weapon)
        return weapon
    }

    private fun downloadJson(uri: URI): JsonNode? {
        val response = send(uri) ?: return null
        if (response.statusCode() !in 200..299 || response.body().isEmpty()) {
            logger.warn("Download of JSON resource {} failed with HTTP {}", uri, response.statusCode())
            return null
        }

        return runCatching { objectMapper.readTree(response.body()) }
            .onFailure { logger.warn("Downloaded JSON resource {} could not be parsed", uri, it) }
            .getOrNull()
    }

    private fun downloadImage(uri: URI): LoadedImage? {
        val response = send(uri) ?: return null
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

        return LoadedImage(bytes, contentType)
    }

    private fun send(uri: URI): HttpResponse<ByteArray>? {
        val request = HttpRequest.newBuilder(uri)
            .timeout(properties.requestTimeout)
            .header("Accept", "application/json,image/*;q=0.9,*/*;q=0.1")
            .header("User-Agent", "GenshinApp/1.0")
            .GET()
            .build()

        return runCatching {
            httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        }.onFailure {
            logger.warn("Could not download {}", uri, it)
        }.getOrNull()
    }

    private fun defaultMaterialImageUrl(name: String): String {
        return fandomImageUrlResolver.itemImageUrl(name)
    }

    private fun defaultTalentImageUrl(
        talentName: String,
        normalAttackWeapon: String?,
        normalAttackElement: String?,
    ): String = if (
        !normalAttackWeapon.isNullOrBlank() && !normalAttackElement.isNullOrBlank()
    ) {
        fandomImageUrlResolver.normalAttackImageUrl(normalAttackWeapon, normalAttackElement)
    } else {
        fandomImageUrlResolver.talentImageUrl(talentName)
    }

    private fun defaultTalentImageUrl(
        character: CharacterDefinition,
        talent: CharacterTalent,
    ): String = defaultTalentImageUrl(
        talent.name,
        character.weapon.takeIf { talent.kind == CharacterTalentKind.NORMAL_ATTACK },
        character.element.takeIf { talent.kind == CharacterTalentKind.NORMAL_ATTACK },
    )

    private fun readClasspathJson(path: String): JsonNode? {
        val resource = ClassPathResource(path)
        if (!resource.exists()) return null

        return runCatching {
            resource.inputStream.use(objectMapper::readTree)
        }.onFailure {
            logger.warn("Bundled character data {} is invalid; using download fallback", path, it)
        }.getOrNull()?.takeIf(::validCharacterJson)
    }

    private fun readCachedJson(path: Path): JsonNode? {
        if (!Files.isRegularFile(path)) return null

        return runCatching {
            Files.newInputStream(path).use(objectMapper::readTree)
        }.onFailure {
            logger.warn("Cached character data {} is invalid; using download fallback", path, it)
        }.getOrNull()?.takeIf(::validCharacterJson)
    }

    private fun readClasspathImage(path: String, contentType: String): LoadedImage? {
        val resource = ClassPathResource(path)
        if (!resource.exists()) return null

        return runCatching {
            LoadedImage(resource.inputStream.use { it.readAllBytes() }, contentType)
        }.onFailure {
            logger.warn("Could not read bundled image {}", path, it)
        }.getOrNull()
    }

    private fun loadRemoteImage(path: Path, sourceUrl: String): LoadedImage? {
        val validatedUrl = validateImageUrl(sourceUrl) ?: return null
        readCachedImage(path, sourceUrl)?.let { return it }

        return synchronized(lockFor("remote-image:$path")) {
            readCachedImage(path, sourceUrl)
                ?: downloadImage(URI.create(validatedUrl))
                    ?.also { writeCachedImage(path, it, validatedUrl) }
        }
    }

    private fun readCachedImage(path: Path, expectedSourceUrl: String): LoadedImage? {
        if (!Files.isRegularFile(path)) return null

        return runCatching {
            val sourcePath = sourceUrlPath(path)
            if (!Files.isRegularFile(sourcePath)) return@runCatching null
            if (Files.readString(sourcePath).trim() != expectedSourceUrl) return@runCatching null

            val bytes = Files.readAllBytes(path)
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_SIZE) return@runCatching null

            val typePath = contentTypePath(path)
            val cachedType = if (Files.isRegularFile(typePath)) Files.readString(typePath).trim() else null
            val contentType = cachedType?.takeIf { it.startsWith("image/") }
                ?: detectImageContentType(bytes)
                ?: return@runCatching null
            LoadedImage(bytes, contentType)
        }.onFailure {
            logger.warn("Could not read cached image {}", path, it)
        }.getOrNull()
    }

    private fun writeCachedJson(path: Path, json: JsonNode) {
        runCatching {
            writeAtomically(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(json))
        }.onFailure {
            logger.warn("Could not cache character data at {}", path, it)
        }
    }

    private fun writeCachedImage(path: Path, image: LoadedImage, sourceUrl: String) {
        runCatching {
            writeAtomically(path, image.bytes)
            writeAtomically(contentTypePath(path), image.contentType.toByteArray(StandardCharsets.UTF_8))
            writeAtomically(sourceUrlPath(path), sourceUrl.toByteArray(StandardCharsets.UTF_8))
        }.onFailure {
            logger.warn("Could not cache image at {}", path, it)
        }
    }

    private fun writeAtomically(path: Path, bytes: ByteArray) {
        val parent = path.parent
        require(parent.startsWith(cacheDirectory)) { "Cache path escaped its configured directory" }
        Files.createDirectories(parent)

        val temporaryFile = Files.createTempFile(parent, path.fileName.toString(), ".tmp")
        try {
            Files.write(temporaryFile, bytes)
            try {
                Files.move(
                    temporaryFile,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
    }

    private fun validCharacterJson(node: JsonNode): Boolean =
        node.isObject && node.path("name").isTextual && node.path("name").asText().isNotBlank()

    private fun validWeaponJson(node: JsonNode): Boolean =
        node.isObject &&
            node.path("name").isTextual &&
            node.path("rarity").canConvertToInt() &&
            node.path("costs").isObject

    private fun characterDataPath(key: String): Path =
        cacheDirectory.resolve("characters").resolve("data").resolve("$key.json").normalize()

    private fun characterImagePath(
        key: String,
        imageType: CharacterImageType,
    ): Path = cacheDirectory
        .resolve("characters")
        .resolve("images")
        .resolve("${key}-${imageType.key}.image")
        .normalize()

    private fun talentImagePath(characterKey: String, talentKey: String): Path =
        cacheDirectory
            .resolve("characters")
            .resolve("talents")
            .resolve("$characterKey-$talentKey.image")
            .normalize()

    private fun materialImagePath(id: Int): Path =
        cacheDirectory.resolve("materials").resolve("$id.image").normalize()

    private fun artifactImagePath(setKey: String, slotKey: String): Path =
        cacheDirectory.resolve("artifacts").resolve("$setKey-$slotKey.image").normalize()

    private fun weaponImagePath(key: String): Path =
        cacheDirectory.resolve("weapons").resolve("$key.image").normalize()

    private fun weaponFullImagePath(key: String): Path =
        cacheDirectory.resolve("weapons").resolve("$key-full.image").normalize()

    private fun weaponDataPath(key: String): Path =
        cacheDirectory.resolve("weapons").resolve("data").resolve("$key.json").normalize()

    private fun contentTypePath(imagePath: Path): Path =
        imagePath.resolveSibling("${imagePath.fileName}.content-type")

    private fun sourceUrlPath(imagePath: Path): Path =
        imagePath.resolveSibling("${imagePath.fileName}.source-url")

    private fun normalizeCharacterKey(key: String): String? =
        key.trim().lowercase().takeIf { it.matches(CHARACTER_KEY_PATTERN) }

    private fun safeMaterialFileName(name: String): String =
        name.replace(" ", "_").replace(INVALID_FILE_NAME_CHARACTERS, "_")

    private fun lockFor(key: String): Any = downloadLocks.computeIfAbsent(key) { Any() }

    private fun cachedImageMatches(path: Path, expectedSourceUrl: String?): Boolean {
        if (expectedSourceUrl == null || !Files.isRegularFile(path)) return false
        val sourcePath = sourceUrlPath(path)
        return Files.isRegularFile(sourcePath) &&
            runCatching { Files.readString(sourcePath).trim() == expectedSourceUrl }.getOrDefault(false)
    }

    private fun effectiveCharacterImageUrl(
        character: CharacterDefinition,
        imageType: CharacterImageType,
    ): String? = imageUrlRegistry
        .characterLink(character.imageResourceKey, imageType)
        ?.effectiveUrl
        ?: character.remoteImageUrl(imageType)

    private fun validateImageUrl(url: String): String? {
        val trimmedUrl = url.trim()
        if (trimmedUrl.length !in 1..MAX_URL_LENGTH) return null
        val uri = runCatching { URI.create(trimmedUrl) }.getOrNull() ?: return null
        return trimmedUrl.takeIf {
            uri.scheme.equals("http", ignoreCase = true) ||
                uri.scheme.equals("https", ignoreCase = true)
        }?.takeIf { !uri.host.isNullOrBlank() }
    }

    private fun detectImageContentType(bytes: ByteArray): String? = when {
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

        else -> null
    }

    data class LoadedImage(
        val bytes: ByteArray,
        val contentType: String,
    )

    data class ImageUpdateResult(
        val successful: Boolean,
        val messageKey: String,
        val messageArguments: Array<out Any?> = emptyArray(),
    )

    enum class ImageState(val messageKey: String) {
        BUNDLED("images.state.bundled"),
        CACHED("images.state.cached"),
        REMOTE("images.state.remote"),
        MISSING("images.state.missing"),
    }

    companion object {
        private val CHARACTER_KEY_PATTERN = Regex("[a-z0-9_-]+")
        private val ARTIFACT_KEY_PATTERN = Regex("[a-z0-9]+")
        private val ARTIFACT_SLOT_KEYS = setOf("flower", "plume", "sands", "goblet", "circlet")
        private val INVALID_FILE_NAME_CHARACTERS = Regex("""[\\/:*?"<>|]""")
        private const val MAX_IMAGE_SIZE = 20 * 1024 * 1024
        private const val MAX_MATERIAL_NAME_LENGTH = 160
        private const val MAX_URL_LENGTH = 4_096
    }
}
