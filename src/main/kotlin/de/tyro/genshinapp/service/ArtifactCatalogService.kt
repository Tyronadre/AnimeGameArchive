package de.tyro.genshinapp.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerArtifact
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder

@Service
class ArtifactCatalogService(
    objectMapper: ObjectMapper,
) {
    private val artifactSets: Map<String, ArtifactSetDefinition> =
        ClassPathResource(CATALOG_RESOURCE).inputStream.use {
            objectMapper.readValue(
                it,
                object : TypeReference<Map<String, ArtifactSetDefinition>>() {},
            )
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

    data class ArtifactSetDefinition(
        val setName: String,
        val pieces: Map<String, String>,
    )

    companion object {
        private const val CATALOG_RESOURCE = "data/artifact-pieces.json"
    }
}
