package de.tyro.genshinapp.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.repository.GenshinStaticDataRepository
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder

@Service
class ArtifactCatalogService(
    private val objectMapper: ObjectMapper,
    private val staticDataRepository: GenshinStaticDataRepository? = null,
) {
    private val bundledArtifactSets: Map<String, ArtifactSetDefinition> =
        ClassPathResource(CATALOG_RESOURCE).inputStream.use {
            objectMapper.readValue(
                it,
                object : TypeReference<Map<String, ArtifactSetDefinition>>() {},
            )
        }

    @Volatile
    private var artifactSets: Map<String, ArtifactSetDefinition> = loadArtifactSets()

    fun refreshFromDatabase() {
        artifactSets = loadArtifactSets()
    }

    fun pieceName(setKey: String, slotKey: String): String? =
        artifactSets[GoodKeyNormalizer.normalize(setKey)]
            ?.pieces
            ?.get(slotKey.lowercase())

    fun setName(setKey: String): String? =
        artifactSets[GoodKeyNormalizer.normalize(setKey)]?.setName

    fun allSets(): Map<String, String> =
        artifactSets.mapValues { it.value.setName }

    fun imageUrl(setKey: String, slotKey: String): String? {
        if (pieceName(setKey, slotKey) == null) return null
        return UriComponentsBuilder.fromPath("/media/artifacts/{setKey}/{slotKey}")
            .buildAndExpand(setKey, slotKey.lowercase())
            .encode()
            .toUriString()
    }

    fun imageUrls(artifacts: Collection<PlayerArtifact>): Map<String, String> =
        artifacts.mapNotNull { artifact ->
            imageUrl(artifact.setKey, artifact.slotKey)?.let {
                artifact.imageKey to it
            }
        }.toMap()

    private fun loadArtifactSets(): Map<String, ArtifactSetDefinition> {
        val imported = staticDataRepository
            ?.findAllByFolderOrderByNameAsc(ARTIFACTS_FOLDER)
            .orEmpty()
            .mapNotNull { entity ->
                runCatching { objectMapper.readTree(entity.sourceJson) }
                    .getOrNull()
                    ?.let(::toDefinition)
            }
            .toMap()
        return bundledArtifactSets + imported
    }

    private fun toDefinition(root: JsonNode): Pair<String, ArtifactSetDefinition>? {
        val setName = root.path("name").asText().trim()
        if (setName.isBlank()) return null
        val key = GoodKeyNormalizer.normalize(setName)
        if (key.isBlank()) return null
        val pieces = ARTIFACT_SLOTS.mapNotNull { (slotKey, sourceField) ->
            val pieceName = root.path(sourceField).path("name").asText().trim()
                .ifBlank { root.path("images").path("name$sourceField").asText().trim() }
            pieceName.takeIf(String::isNotBlank)?.let { slotKey to it }
        }.toMap()
        if (pieces.isEmpty()) return null
        return key to ArtifactSetDefinition(setName, pieces)
    }

    data class ArtifactSetDefinition(
        val setName: String,
        val pieces: Map<String, String>,
    )

    companion object {
        private const val CATALOG_RESOURCE = "data/artifact-pieces.json"
        private const val ARTIFACTS_FOLDER = "artifacts"
        private val ARTIFACT_SLOTS = linkedMapOf(
            "flower" to "flower",
            "plume" to "plume",
            "sands" to "sands",
            "goblet" to "goblet",
            "circlet" to "circlet",
        )
    }
}
