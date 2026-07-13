package de.tyro.genshinapp.service

import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterImageType
import de.tyro.genshinapp.model.MaterialDefinition
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

@Service
class ImageUrlRegistry(
    private val objectMapper: ObjectMapper,
    properties: GenshinContentProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val registryFile = Path.of(properties.cacheDirectory)
        .toAbsolutePath()
        .normalize()
        .resolve("image-links.json")
    private val lock = Any()
    private var document: ImageLinksDocument = ImageLinksDocument()
    private var fileStamp: FileStamp? = null

    init {
        Files.createDirectories(registryFile.parent)
        if (Files.notExists(registryFile)) {
            writeDocument()
        } else {
            document = readDocument(registryFile)
            fileStamp = currentFileStamp()
        }
    }

    fun registerDefaults(
        characters: Collection<CharacterDefinition>,
        materials: Collection<Pair<MaterialDefinition, String>>,
        talents: Collection<TalentImageDefault> = emptyList(),
    ) = synchronized(lock) {
        refreshIfChanged()
        var changed = false

        characters.forEach { character ->
            val legacyEntry = document.characters.remove(character.key)
            if (legacyEntry != null) changed = true

            CharacterImageType.entries.forEach { imageType ->
                val entry = document.characters.getOrPut(characterEntryKey(character.key, imageType)) {
                    changed = true
                    EditableImageLink()
                }
                if (
                    imageType == CharacterImageType.WISH &&
                    entry.url.isBlank() &&
                    legacyEntry?.url?.isNotBlank() == true
                ) {
                    entry.url = legacyEntry.url
                    changed = true
                }
                changed = entry.updateDefaults(
                    "${character.name} ${imageType.label}",
                    character.remoteImageUrl(imageType).orEmpty(),
                ) || changed
            }
        }

        materials.forEach { (material, defaultUrl) ->
            val entry = document.materials.getOrPut(material.id.toString()) {
                changed = true
                EditableImageLink()
            }
            changed = entry.updateDefaults(material.name, defaultUrl) || changed
        }

        talents.forEach { talent ->
            val entry = document.talents.getOrPut(
                talentEntryKey(talent.characterKey, talent.talentKey),
            ) {
                changed = true
                EditableImageLink()
            }
            changed = entry.updateDefaults(talent.name, talent.defaultUrl) || changed
        }

        if (changed) writeDocument()
    }

    fun characterLink(
        key: String,
        imageType: CharacterImageType,
    ): EditableImageLink? = synchronized(lock) {
        refreshIfChanged()
        document.characters[characterEntryKey(key, imageType)]?.copy()
    }

    fun materialLink(id: Int): EditableImageLink? = synchronized(lock) {
        refreshIfChanged()
        document.materials[id.toString()]?.copy()
    }

    fun talentLink(characterKey: String, talentKey: String): EditableImageLink? =
        synchronized(lock) {
            refreshIfChanged()
            document.talents[talentEntryKey(characterKey, talentKey)]?.copy()
        }

    fun setCharacterOverride(
        key: String,
        imageType: CharacterImageType,
        name: String,
        url: String,
    ) = synchronized(lock) {
        refreshIfChanged()
        val entry = document.characters.getOrPut(characterEntryKey(key, imageType)) {
            EditableImageLink(name = "$name ${imageType.label}")
        }
        entry.name = "$name ${imageType.label}"
        entry.url = url
        writeDocument()
    }

    fun setMaterialOverride(id: Int, name: String, url: String) = synchronized(lock) {
        refreshIfChanged()
        val entry = document.materials.getOrPut(id.toString()) {
            EditableImageLink(name = name)
        }
        entry.name = name
        entry.url = url
        writeDocument()
    }

    fun setTalentOverride(
        characterKey: String,
        talentKey: String,
        name: String,
        url: String,
    ) = synchronized(lock) {
        refreshIfChanged()
        val entry = document.talents.getOrPut(talentEntryKey(characterKey, talentKey)) {
            EditableImageLink(name = name)
        }
        entry.name = name
        entry.url = url
        writeDocument()
    }

    fun resetCharacterOverride(
        key: String,
        imageType: CharacterImageType,
    ) = synchronized(lock) {
        refreshIfChanged()
        document.characters[characterEntryKey(key, imageType)]?.let {
            it.url = ""
            writeDocument()
        }
    }

    fun resetMaterialOverride(id: Int) = synchronized(lock) {
        refreshIfChanged()
        document.materials[id.toString()]?.let {
            it.url = ""
            writeDocument()
        }
    }

    fun resetTalentOverride(characterKey: String, talentKey: String) = synchronized(lock) {
        refreshIfChanged()
        document.talents[talentEntryKey(characterKey, talentKey)]?.let {
            it.url = ""
            writeDocument()
        }
    }

    fun filePath(): Path = registryFile

    private fun characterEntryKey(key: String, imageType: CharacterImageType): String =
        "${key.lowercase()}:${imageType.key}"

    private fun talentEntryKey(characterKey: String, talentKey: String): String =
        "${characterKey.lowercase()}:${talentKey.lowercase()}"

    private fun refreshIfChanged() {
        val currentStamp = currentFileStamp()
        if (currentStamp == null || currentStamp == fileStamp) return

        runCatching { readDocument(registryFile) }
            .onSuccess {
                document = it
                fileStamp = currentStamp
            }
            .onFailure {
                logger.error(
                    "Image link file {} is invalid. Keeping the last valid version.",
                    registryFile,
                    it,
                )
            }
    }

    private fun readDocument(path: Path): ImageLinksDocument =
        Files.newInputStream(path).use {
            objectMapper.readValue(it, ImageLinksDocument::class.java)
        }

    private fun writeDocument() {
        val bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document.sorted())
        val temporaryFile = Files.createTempFile(registryFile.parent, "image-links", ".tmp")
        try {
            Files.write(temporaryFile, bytes)
            try {
                Files.move(
                    temporaryFile,
                    registryFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile, registryFile, StandardCopyOption.REPLACE_EXISTING)
            }
            fileStamp = currentFileStamp()
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
    }

    private fun currentFileStamp(): FileStamp? {
        if (!Files.isRegularFile(registryFile)) return null
        return FileStamp(
            modified = Files.getLastModifiedTime(registryFile),
            size = Files.size(registryFile),
        )
    }

    private data class FileStamp(
        val modified: FileTime,
        val size: Long,
    )
}

data class ImageLinksDocument(
    var characters: MutableMap<String, EditableImageLink> = linkedMapOf(),
    var materials: MutableMap<String, EditableImageLink> = linkedMapOf(),
    var talents: MutableMap<String, EditableImageLink> = linkedMapOf(),
) {
    fun sorted(): ImageLinksDocument = ImageLinksDocument(
        characters = characters.toSortedMap(),
        materials = materials.toSortedMap(
            compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it },
        ),
        talents = talents.toSortedMap(),
    )
}

data class TalentImageDefault(
    val characterKey: String,
    val talentKey: String,
    val name: String,
    val defaultUrl: String,
)

data class EditableImageLink(
    var name: String = "",
    var defaultUrl: String = "",
    var url: String = "",
) {
    val effectiveUrl: String?
        get() = url.ifBlank { defaultUrl }.ifBlank { null }

    val hasOverride: Boolean
        get() = url.isNotBlank()

    fun updateDefaults(newName: String, newDefaultUrl: String): Boolean {
        if (name == newName && defaultUrl == newDefaultUrl) return false
        name = newName
        defaultUrl = newDefaultUrl
        return true
    }
}
