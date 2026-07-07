package de.tyro.genshinapp.service

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.GoodKeyNormalizer
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service

@Service
class ArtifactOptimizerBuildProfileService(
    objectMapper: ObjectMapper,
) {
    private val catalog: ArtifactOptimizerBuildCatalog =
        ClassPathResource(CATALOG_RESOURCE).takeIf { it.exists() }
            ?.inputStream
            ?.use {
                objectMapper.readValue(
                    it,
                    object : TypeReference<ArtifactOptimizerBuildCatalog>() {},
                )
            }
            ?: ArtifactOptimizerBuildCatalog()

    fun findAll(characterKey: String): List<ArtifactOptimizerBuildProfile> =
        catalog.profilesByCharacter[GoodKeyNormalizer.normalize(characterKey)].orEmpty()

    fun find(characterKey: String, selectionKey: String?): ArtifactOptimizerBuildProfile? {
        val id = selectionKey?.removePrefix(SOURCE_PROFILE_PREFIX)
            ?.takeIf { selectionKey.startsWith(SOURCE_PROFILE_PREFIX) }
            ?: return null
        return findAll(characterKey).find { it.id == id }
    }

    fun profileFor(build: ArtifactOptimizerBuildProfile): ArtifactOptimizationProfile =
        ArtifactOptimizationProfile.fromKey(build.profileKey)

    fun targetsFor(
        build: ArtifactOptimizerBuildProfile,
        artifactOptimizationService: ArtifactOptimizationService,
    ): ArtifactOptimizationTargets =
        artifactOptimizationService.createTargets(
            profile = profileFor(build),
            custom = true,
            requestedMainStats = build.fixedMainStats,
            requestedPriorityStats = build.substatKeys,
        )

    fun setSelectionFor(
        build: ArtifactOptimizerBuildProfile,
        artifactOptimizationService: ArtifactOptimizationService,
        availableSetKeys: Collection<String>,
    ): ArtifactSetSelection =
        artifactOptimizationService.createSetSelection(
            modeKey = if (build.artifactSets.isEmpty()) {
                ArtifactSetSelectionMode.CURRENT.key
            } else {
                ArtifactSetSelectionMode.CUSTOM.key
            },
            requestedTargets = build.artifactSets.map {
                ArtifactSetTarget(it.key, it.count)
            },
            availableSetKeys = availableSetKeys,
        )

    companion object {
        const val SOURCE_PROFILE_PREFIX = "source-"
        private const val CATALOG_RESOURCE = "data/artifact-optimizer-builds.json"
    }
}

data class ArtifactOptimizerBuildCatalog(
    val sourceName: String = "",
    val sourceUrl: String = "",
    val sourceLastUpdated: String? = null,
    val scrapedAt: String? = null,
    val profiles: List<ArtifactOptimizerBuildProfile> = emptyList(),
) {
    @get:JsonIgnore
    val profilesByCharacter: Map<String, List<ArtifactOptimizerBuildProfile>>
        get() = profiles.groupBy { GoodKeyNormalizer.normalize(it.characterKey) }
}

data class ArtifactOptimizerBuildProfile(
    val id: String = "",
    val characterKey: String = "",
    val characterName: String = "",
    val characterUrl: String? = null,
    val buildName: String = "",
    val profileKey: String = ArtifactOptimizationProfile.ATTACK.key,
    val weaponName: String? = null,
    val artifactSets: List<ArtifactOptimizerBuildSetRecommendation> = emptyList(),
    val mainStats: Map<String, ArtifactOptimizerBuildStatRecommendation> = emptyMap(),
    val substats: List<ArtifactOptimizerBuildStatRecommendation> = emptyList(),
) {
    @get:JsonIgnore
    val selectionKey: String
        get() = ArtifactOptimizerBuildProfileService.SOURCE_PROFILE_PREFIX + id

    @get:JsonIgnore
    val displayName: String
        get() = listOfNotNull("Game8", buildName.takeIf(String::isNotBlank))
            .joinToString(" - ")

    @get:JsonIgnore
    val fixedMainStats: Map<String, String?>
        get() = listOf("sands", "goblet", "circlet").associateWith { slot ->
            mainStats[slot]?.fixedTargetFor(slot)
        }

    @get:JsonIgnore
    val substatKeys: List<String>
        get() = substats
            .flatMap(ArtifactOptimizerBuildStatRecommendation::keys)
            .distinct()
}

data class ArtifactOptimizerBuildSetRecommendation(
    val name: String = "",
    val key: String = "",
    val count: Int = 2,
)

data class ArtifactOptimizerBuildStatRecommendation(
    val raw: String = "",
    val keys: List<String> = emptyList(),
) {
    fun fixedTargetFor(slot: String): String? =
        when {
            keys.isEmpty() -> null
            slot == "circlet" && keys.toSet() == FLEXIBLE_CRIT_CIRCLET_KEYS -> null
            else -> keys.first()
        }

    companion object {
        private val FLEXIBLE_CRIT_CIRCLET_KEYS = setOf("critRate_", "critDMG_")
    }
}
