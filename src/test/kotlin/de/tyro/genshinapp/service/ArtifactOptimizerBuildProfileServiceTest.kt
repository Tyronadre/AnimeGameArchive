package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.model.PlayerWeapon
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ArtifactOptimizerBuildProfileServiceTest {
    @Test
    fun `source goal ranges become optimizer minimums maximums and priorities`() {
        val mapper = jacksonObjectMapper()
        val profileService = ArtifactOptimizerBuildProfileService(mapper)
        val optimizationService = ArtifactOptimizationService(ArtifactCatalogService(mapper))
        val build = ArtifactOptimizerBuildProfile(
            profileKey = ArtifactOptimizationProfile.REACTION.key,
            substats = listOf(
                ArtifactOptimizerBuildStatRecommendation("Energy Recharge", listOf("enerRech_")),
                ArtifactOptimizerBuildStatRecommendation("Elemental Mastery", listOf("eleMas")),
            ),
            goalStats = listOf(
                goal("Elemental Mastery", "eleMas", 700.0, 800.0),
                ArtifactOptimizerBuildGoalStatRecommendation(
                    stat = "Energy Recharge",
                    goalValue = "150~180% (if Solo Hydro)\n110~130% (if Double Hydro)",
                    keys = listOf("enerRech_"),
                    ranges = listOf(
                        ArtifactOptimizerBuildGoalRange(150.0, 180.0, "if Solo Hydro"),
                        ArtifactOptimizerBuildGoalRange(110.0, 130.0, "if Double Hydro"),
                    ),
                ),
                goal("CRIT Rate", "critRate_", 50.0, 70.0),
                goal("CRIT DMG", "critDMG_", 100.0, 120.0),
                ArtifactOptimizerBuildGoalStatRecommendation(
                    stat = "DEF",
                    goalValue = "Little to no DEF needed",
                    keys = listOf("def"),
                ),
            ),
        )

        val targets = profileService.targetsFor(build, optimizationService)

        assertEquals(
            listOf("eleMas", "enerRech_", "critRate_", "critDMG_"),
            targets.substatPriorities,
        )
        assertEquals(
            mapOf(
                "eleMas" to 700.0,
                "enerRech_" to 150.0,
                "critRate_" to 50.0,
                "critDMG_" to 100.0,
            ),
            targets.minimumTargets,
        )
        assertEquals(
            mapOf(
                "critRate_" to 70.0,
                "eleMas" to 800.0,
                "enerRech_" to 180.0,
                "critDMG_" to 120.0,
            ),
            targets.maximumTargets,
        )
    }

    @Test
    fun `recommendation ownership matches imported characters weapons and overrides`() {
        val service = ArtifactOptimizerBuildProfileService(jacksonObjectMapper())
        val snapshot = PlayerSnapshot(
            formatVersion = 2,
            source = "GOOD",
            importedAt = Instant.EPOCH,
            characters = listOf(
                character("Furina"),
                character("Aether"),
            ),
            inventory = emptyMap(),
            inventoryNames = emptyMap(),
            exportedInventoryKeys = 0,
            artifacts = emptyList(),
            weapons = listOf(weapon("WolfsGravestone")),
        )
        val build = ArtifactOptimizerBuildProfile(
            recommendedWeapons = listOf(
                ArtifactOptimizerBuildWeaponRecommendation(name = "Wolf's Gravestone"),
                ArtifactOptimizerBuildWeaponRecommendation(name = "Aquila Favonia"),
            ),
            recommendedTeams = listOf(
                ArtifactOptimizerBuildTeamRecommendation(
                    lineups = listOf(
                        ArtifactOptimizerBuildTeamLineup(
                            slots = listOf(
                                ArtifactOptimizerBuildTeamSlot(
                                    members = listOf(
                                        ArtifactOptimizerBuildTeamMember(
                                            name = "Furina",
                                            characterKey = "furina",
                                        ),
                                        ArtifactOptimizerBuildTeamMember(
                                            name = "Traveler (Dendro)",
                                            characterKey = "traveler",
                                        ),
                                        ArtifactOptimizerBuildTeamMember(
                                            name = "Nahida",
                                            characterKey = "nahida",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val ownership = service.recommendationOwnership(
            build,
            snapshot,
            characterOwnershipOverrides = mapOf("nahida" to true, "furina" to false),
        )

        assertEquals(false, ownership.characters["Furina"])
        assertEquals(true, ownership.characters["Traveler (Dendro)"])
        assertEquals(true, ownership.characters["Nahida"])
        assertEquals(true, ownership.weapons["Wolf's Gravestone"])
        assertEquals(false, ownership.weapons["Aquila Favonia"])
        assertFalse(ArtifactOptimizerBuildWeaponRecommendation(name = "Weapons").hasInternalPage)
    }

    private fun goal(
        stat: String,
        key: String,
        minimum: Double,
        maximum: Double,
    ) = ArtifactOptimizerBuildGoalStatRecommendation(
        stat = stat,
        goalValue = "$minimum~$maximum",
        keys = listOf(key),
        ranges = listOf(ArtifactOptimizerBuildGoalRange(minimum, maximum)),
    )

    private fun character(key: String) = PlayerCharacterState(
        key = key,
        level = 90,
        constellation = 0,
        ascension = 6,
        normalTalent = 1,
        skillTalent = 1,
        burstTalent = 1,
    )

    private fun weapon(key: String) = PlayerWeapon(
        key = key,
        level = 90,
        ascension = 6,
        refinement = 1,
        location = null,
        locked = false,
    )
}
