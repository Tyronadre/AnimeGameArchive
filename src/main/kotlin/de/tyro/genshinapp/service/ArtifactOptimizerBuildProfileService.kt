package de.tyro.genshinapp.service

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.model.TravelerIdentity
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
            requestedPriorityStats = build.goalStatKeys + build.substatKeys,
            requestedMinimumTargets = build.goalMinimumTargets,
            requestedMaximumTargets = build.goalMaximumTargets,
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

    fun recommendationOwnership(
        build: ArtifactOptimizerBuildProfile,
        snapshot: PlayerSnapshot?,
        characterOwnershipOverrides: Map<String, Boolean>,
    ): ArtifactOptimizerBuildRecommendationOwnership {
        val importedCharacterKeys = snapshot?.characters.orEmpty()
            .mapTo(mutableSetOf()) {
                TravelerIdentity.canonicalCharacterKey(it.key)
            }
        val importedWeaponKeys = snapshot?.weapons.orEmpty()
            .mapTo(mutableSetOf()) { GoodKeyNormalizer.normalize(it.key) }
        val characters = build.recommendedTeams
            .flatMap(ArtifactOptimizerBuildTeamRecommendation::lineups)
            .flatMap(ArtifactOptimizerBuildTeamLineup::displaySlots)
            .flatMap(ArtifactOptimizerBuildTeamSlot::members)
            .distinctBy(ArtifactOptimizerBuildTeamMember::name)
            .associate { member ->
                val characterKey = TravelerIdentity.canonicalCharacterKey(
                    member.characterKey ?: member.name,
                )
                member.name to (
                    characterOwnershipOverrides[characterKey]
                        ?: (characterKey in importedCharacterKeys)
                    )
            }
        val weapons = build.recommendedWeapons.associate { weapon ->
            weapon.name to (weapon.weaponKey in importedWeaponKeys)
        }
        return ArtifactOptimizerBuildRecommendationOwnership(characters, weapons)
    }

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
    val goalStats: List<ArtifactOptimizerBuildGoalStatRecommendation> = emptyList(),
    val goalNotes: List<String> = emptyList(),
    val recommendedWeapons: List<ArtifactOptimizerBuildWeaponRecommendation> = emptyList(),
    val recommendedTeams: List<ArtifactOptimizerBuildTeamRecommendation> = emptyList(),
) {
    @get:JsonIgnore
    val characterPageKey: String
        get() = if (GoodKeyNormalizer.normalize(characterKey).startsWith("traveler")) {
            "traveler"
        } else {
            characterKey
        }

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

    @get:JsonIgnore
    val goalStatKeys: List<String>
        get() = goalStats
            .filter { it.primaryRange != null }
            .flatMap(ArtifactOptimizerBuildGoalStatRecommendation::keys)
            .distinct()

    @get:JsonIgnore
    val goalMinimumTargets: Map<String, Double>
        get() = goalStats.mapNotNull { recommendation ->
            recommendation.keys.singleOrNull()?.let { key ->
                recommendation.primaryRange?.minimum?.let { key to it }
            }
        }.toMap()

    @get:JsonIgnore
    val goalMaximumTargets: Map<String, Double>
        get() = goalStats.mapNotNull { recommendation ->
            recommendation.keys.singleOrNull()?.let { key ->
                recommendation.primaryRange?.maximum?.let { key to it }
            }
        }.toMap()
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

data class ArtifactOptimizerBuildGoalStatRecommendation(
    val stat: String = "",
    val goalValue: String = "",
    val keys: List<String> = emptyList(),
    val ranges: List<ArtifactOptimizerBuildGoalRange> = emptyList(),
) {
    @get:JsonIgnore
    val primaryRange: ArtifactOptimizerBuildGoalRange?
        get() = ranges.firstOrNull()
}

data class ArtifactOptimizerBuildGoalRange(
    val minimum: Double? = null,
    val maximum: Double? = null,
    val condition: String? = null,
)

data class ArtifactOptimizerBuildWeaponRecommendation(
    val rank: Int = 0,
    val name: String = "",
    val url: String? = null,
    val category: String? = null,
    val obtainMethod: String? = null,
) {
    @get:JsonIgnore
    val weaponKey: String
        get() = GoodKeyNormalizer.normalize(name)

    @get:JsonIgnore
    val hasInternalPage: Boolean
        get() = weaponKey !in GENERIC_WEAPON_KEYS

    @get:JsonIgnore
    val freeToPlay: Boolean
        get() = category?.let {
            "free-to-play" in it.lowercase() || "f2p" in it.lowercase()
        } == true

    companion object {
        private val GENERIC_WEAPON_KEYS = setOf("weapon", "weapons", "recommendedweapons")
    }
}

data class ArtifactOptimizerBuildTeamRecommendation(
    val name: String = "",
    val lineups: List<ArtifactOptimizerBuildTeamLineup> = emptyList(),
    val notes: List<String> = emptyList(),
)

data class ArtifactOptimizerBuildTeamLineup(
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val members: List<ArtifactOptimizerBuildTeamMember> = emptyList(),
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val slots: List<ArtifactOptimizerBuildTeamSlot> = emptyList(),
) {
    @get:JsonIgnore
    val displaySlots: List<ArtifactOptimizerBuildTeamSlot>
        get() = slots.ifEmpty {
            members.map { member ->
                ArtifactOptimizerBuildTeamSlot(member.role, listOf(member))
            }
        }
}

data class ArtifactOptimizerBuildTeamSlot(
    val role: String = "",
    val members: List<ArtifactOptimizerBuildTeamMember> = emptyList(),
)

data class ArtifactOptimizerBuildTeamMember(
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val role: String = "",
    val name: String = "",
    val url: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val characterKey: String? = null,
)

data class ArtifactOptimizerBuildRecommendationOwnership(
    val characters: Map<String, Boolean> = emptyMap(),
    val weapons: Map<String, Boolean> = emptyMap(),
)
