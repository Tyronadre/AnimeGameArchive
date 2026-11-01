package de.tyro.genshinapp.service.presentation

import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.GoodKeyNormalizer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
@ConfigurationProperties("genshin.presentation")
class CharacterStageProperties {
    var modelDirectory: String = ""
}

data class CharacterStageEntry(
    val key: String,
    val aliases: List<String>,
    val directory: String,
    val model: String,
    val assets: List<String>,
    val profile: String,
    val variant: String,
    val sourceSha256: String,
    val bundled: Boolean = false,
    val fittedSkirtClearance: Boolean = false,
)

data class CharacterStageManifest(val models: List<CharacterStageEntry>)

@Component
class CharacterStageCatalog(
    private val properties: CharacterStageProperties,
    objectMapper: ObjectMapper,
) {
    private val entries = ClassPathResource("data/character-stages.json").inputStream.use {
        objectMapper.readValue<CharacterStageManifest>(it).models
    }
    private val byAlias = entries.flatMap { entry -> entry.aliases.map { it to entry } }.toMap()

    fun find(key: String): CharacterStageEntry? {
        val entry = byAlias[GoodKeyNormalizer.normalize(key)] ?: return null
        return entry.takeIf { it.assets.all { asset -> resource(it, asset) != null } }
    }

    fun asset(key: String, relative: String): Resource? {
        val entry = byAlias[GoodKeyNormalizer.normalize(key)] ?: return null
        return resource(entry, relative.removePrefix("/"))
    }

    private fun resource(entry: CharacterStageEntry, relative: String): Resource? {
        // Only the PMX and textures explicitly referenced by the selected model.
        if (relative !in entry.assets || relative.contains('\\') || relative.split('/').any { it == ".." || it == "." }) return null
        if (entry.bundled) {
            return ClassPathResource("static/character-stage/${entry.directory}/$relative").takeIf { it.exists() }
        }
        if (properties.modelDirectory.isBlank()) return null
        return runCatching {
            val root = Path.of(properties.modelDirectory).toRealPath()
            val directory = root.resolve(entry.directory).normalize()
            val file = directory.resolve(relative).normalize()
            if (!directory.startsWith(root) || !file.startsWith(directory) || !Files.isRegularFile(file)) return null
            // Reject symlinks escaping either the configured library or model folder.
            val realDirectory = directory.toRealPath()
            val realFile = file.toRealPath()
            if (!realDirectory.startsWith(root) || !realFile.startsWith(realDirectory)) return null
            FileSystemResource(realFile)
        }.getOrNull()
    }
}
