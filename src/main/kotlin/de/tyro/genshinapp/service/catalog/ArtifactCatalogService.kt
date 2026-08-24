package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.service.importer.GenshinStaticDataCatalog
import de.tyro.genshinapp.service.importer.HoyolabWikiEntry
import de.tyro.genshinapp.service.importer.isAllowedHoyolabImageUrl
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
    private val staticDataCatalog: GenshinStaticDataCatalog? = null,
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

    @Synchronized
    fun synchronizeHoyolabArtifactList(entries: Collection<HoyolabWikiEntry>): Int {
        val definitions = entries.mapNotNull(::toHoyolabDefinition).toMap()
        if (definitions.isEmpty()) return 0
        val previous = artifactSets
        val nodes = definitions.values.map { definition ->
            objectMapper.createObjectNode().also { root ->
                root.put("name", definition.setName)
                definition.hoyolabEntryId?.let { root.put("hoyolabEntryId", it) }
                definition.twoPieceEffect?.let { root.put("twoPieceEffect", it) }
                definition.fourPieceEffect?.let { root.put("fourPieceEffect", it) }
                root.set<JsonNode>(
                    "imageUrls",
                    objectMapper.valueToTree(definition.remoteImageUrls),
                )
            }
        }
        val result = staticDataCatalog?.synchronize(HOYOLAB_ARTIFACTS_FOLDER, nodes)
        artifactSets = if (result == null) artifactSets + definitions else loadArtifactSets()
        return result?.changedCount ?: definitions.count { (key, value) -> previous[key] != value }
    }

    fun pieceName(setKey: String, slotKey: String): String? =
        artifactSets[GoodKeyNormalizer.normalize(setKey)]
            ?.pieces
            ?.get(slotKey.lowercase())

    fun artifactImage(setKey: String, slotKey: String): ArtifactImageDefinition? {
        val definition = artifactSets[GoodKeyNormalizer.normalize(setKey)] ?: return null
        val normalizedSlot = slotKey.lowercase().takeIf { it in ARTIFACT_SLOTS } ?: return null
        val pieceName = definition.pieces[normalizedSlot]
        val remoteUrl = definition.remoteImageUrls[normalizedSlot]
        if (pieceName == null && remoteUrl == null) return null
        return ArtifactImageDefinition(
            name = pieceName ?: "${definition.setName} ${normalizedSlot.replaceFirstChar { it.uppercase() }}",
            remoteUrl = remoteUrl,
        )
    }

    fun setName(setKey: String): String? =
        artifactSets[GoodKeyNormalizer.normalize(setKey)]?.setName

    fun allSets(): Map<String, String> =
        artifactSets.mapValues { it.value.setName }

    fun imageUrl(setKey: String, slotKey: String): String? {
        if (artifactImage(setKey, slotKey) == null) return null
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
        val hoyolab = staticDataRepository
            ?.findAllByFolderOrderByNameAsc(HOYOLAB_ARTIFACTS_FOLDER)
            .orEmpty()
            .mapNotNull { entity ->
                runCatching { objectMapper.readTree(entity.sourceJson) }
                    .getOrNull()
                    ?.let(::toStoredHoyolabDefinition)
            }
            .toMap()
        return bundledArtifactSets + imported + hoyolab.mapValues { (key, official) ->
            imported[key]?.let { normalized ->
                official.copy(pieces = normalized.pieces.ifEmpty { official.pieces })
            } ?: bundledArtifactSets[key]?.let { bundled ->
                official.copy(pieces = bundled.pieces.ifEmpty { official.pieces })
            } ?: official
        }
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

    private fun toHoyolabDefinition(entry: HoyolabWikiEntry): Pair<String, ArtifactSetDefinition>? {
        val key = GoodKeyNormalizer.normalize(entry.name).takeIf(String::isNotBlank) ?: return null
        val images = HOYOLAB_IMAGE_FIELDS.mapNotNull { (slot, field) ->
            entry.displayFields[field]
                ?.takeIf(::isAllowedHoyolabImageUrl)
                ?.let { slot to it }
        }.toMap()
        if (images.isEmpty()) return null
        return key to ArtifactSetDefinition(
            setName = entry.name,
            remoteImageUrls = images,
            hoyolabEntryId = entry.entryPageId,
            twoPieceEffect = entry.displayFields["two_set_effect"],
            fourPieceEffect = entry.displayFields["four_set_effect"],
        )
    }

    private fun toStoredHoyolabDefinition(
        root: JsonNode,
    ): Pair<String, ArtifactSetDefinition>? {
        val name = root.path("name").asText().trim().takeIf(String::isNotBlank) ?: return null
        val key = GoodKeyNormalizer.normalize(name).takeIf(String::isNotBlank) ?: return null
        val imageUrls = root.path("imageUrls").takeIf(JsonNode::isObject)
            ?.properties()
            ?.mapNotNull { (slot, value) ->
                value.asText().trim().takeIf(String::isNotBlank)?.let { slot to it }
            }
            ?.toMap()
            .orEmpty()
        return key to ArtifactSetDefinition(
            setName = name,
            remoteImageUrls = imageUrls,
            hoyolabEntryId = root.path("hoyolabEntryId").asLong().takeIf { it > 0 },
            twoPieceEffect = root.path("twoPieceEffect").asText()
                .trim()
                .takeIf(String::isNotBlank),
            fourPieceEffect = root.path("fourPieceEffect").asText()
                .trim()
                .takeIf(String::isNotBlank),
        )
    }

    data class ArtifactSetDefinition(
        val setName: String,
        val pieces: Map<String, String> = emptyMap(),
        val remoteImageUrls: Map<String, String> = emptyMap(),
        val hoyolabEntryId: Long? = null,
        val twoPieceEffect: String? = null,
        val fourPieceEffect: String? = null,
    )

    data class ArtifactImageDefinition(
        val name: String,
        val remoteUrl: String? = null,
    )

    companion object {
        private const val CATALOG_RESOURCE = "data/artifact-pieces.json"
        private const val ARTIFACTS_FOLDER = "artifacts"
        private const val HOYOLAB_ARTIFACTS_FOLDER = "hoyolabartifacts"
        private val ARTIFACT_SLOTS = linkedMapOf(
            "flower" to "flower",
            "plume" to "plume",
            "sands" to "sands",
            "goblet" to "goblet",
            "circlet" to "circlet",
        )
        private val HOYOLAB_IMAGE_FIELDS = mapOf(
            "flower" to "flower_of_life_icon_url",
            "plume" to "plume_of_death_icon_url",
            "sands" to "sands_of_eon_icon_url",
            "goblet" to "goblet_of_eonothem_icon_url",
            "circlet" to "circlet_of_logos_icon_url",
        )
    }
}
