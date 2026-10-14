package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.service.importer.HoyolabWikiEntry
import de.tyro.genshinapp.service.importer.HoyolabWikiMenu
import de.tyro.genshinapp.service.importer.isAllowedHoyolabImageUrl
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerArtifact
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder

@Service
class ArtifactCatalogService(
    private val objectMapper: ObjectMapper,
    private val hoyowikiCatalogStore: HoyowikiCatalogStore? = null,
) {
    private val bundledArtifactSets: Map<String, ArtifactSetDefinition> =
        ClassPathResource(CATALOG_RESOURCE).inputStream.use {
            objectMapper.readValue(
                it,
                object : TypeReference<Map<String, ArtifactSetDefinition>>() {},
            )
        }

    @Volatile
    private var artifactSets: Map<String, ArtifactSetDefinition> = bundledArtifactSets

    init { refreshFromDatabase() }

    fun refreshFromDatabase() {
        val stored = hoyowikiCatalogStore?.entries(HoyolabWikiMenu.ARTIFACTS).orEmpty()
        if (stored.isNotEmpty()) synchronizeHoyolabArtifactList(stored)
    }

    @Synchronized
    fun synchronizeHoyolabArtifactList(entries: Collection<HoyolabWikiEntry>): Int {
        val definitions = entries.mapNotNull(::toHoyolabDefinition).toMap()
        if (definitions.isEmpty()) return 0
        val previous = artifactSets
        artifactSets = bundledArtifactSets + definitions.mapValues { (key, official) ->
            bundledArtifactSets[key]?.let { bundled ->
                official.copy(pieces = bundled.pieces.ifEmpty { official.pieces })
            } ?: official
        }
        return definitions.count { (key, value) -> previous[key] != value }
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
