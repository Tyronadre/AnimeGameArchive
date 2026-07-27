package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterImageType
import de.tyro.genshinapp.model.InventoryMaterialBalance
import de.tyro.genshinapp.model.MaterialCost
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialRequirement
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.model.TravelerAppearance
import de.tyro.genshinapp.model.TravelerElement
import de.tyro.genshinapp.model.TravelerSelection
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FarmingDashboardServiceTest {
    private val catalogService = mock(CharacterCatalogService::class.java)
    private val materialCatalogService = mock(MaterialCatalogService::class.java)
    private val targetService = mock(CharacterTargetService::class.java)
    private val materialCalculator = MaterialCalculator(materialCatalogService)
    private val planningService = mock(PlayerPlanningService::class.java)
    private val materialCraftingService = mock(MaterialCraftingService::class.java)
    private val domainScheduleService = mock(DomainScheduleService::class.java)
    private val artifactOptimizerProfileService = mock(ArtifactOptimizerProfileService::class.java)
    private val artifactOptimizationService = mock(ArtifactOptimizationService::class.java)
    private val artifactCatalogService = mock(ArtifactCatalogService::class.java)
    private val characterWeaponTargetService = mock(CharacterWeaponTargetService::class.java)
    private val weaponDataService = mock(WeaponDataService::class.java)
    private val weaponPlanningService = mock(WeaponPlanningService::class.java)
    private val travelerService = mock(TravelerService::class.java)

    private val service = FarmingDashboardService(
        catalogService,
        materialCatalogService,
        targetService,
        materialCalculator,
        planningService,
        materialCraftingService,
        domainScheduleService,
        artifactOptimizerProfileService,
        artifactOptimizationService,
        artifactCatalogService,
        characterWeaponTargetService,
        weaponDataService,
        weaponPlanningService,
        travelerService,
    )

    @Test
    fun `keeps persisted talent book categories out of free recommendations`() {
        val character = character()
        val state = PlayerCharacterState(
            key = character.key,
            level = 80,
            constellation = 0,
            ascension = 6,
            normalTalent = 6,
            skillTalent = 6,
            burstTalent = 6,
        )
        val snapshot = PlayerSnapshot(
            formatVersion = 3,
            source = "Irminsul",
            importedAt = Instant.EPOCH,
            characters = emptyList(),
            inventory = emptyMap(),
            inventoryNames = emptyMap(),
            exportedInventoryKeys = 0,
            artifacts = emptyList(),
            weapons = emptyList(),
        )
        val requirement = MaterialRequirement(
            id = 104302,
            name = "Guide to Freedom",
            amount = 30,
            imageUrl = null,
        )
        val talentBook = InventoryMaterialBalance(
            id = requirement.id,
            name = requirement.name,
            required = requirement.amount,
            owned = 0,
            missing = requirement.amount,
            imageUrl = null,
            category = MaterialCategory.TALENT_BOOK,
        )

        `when`(catalogService.getCharacters()).thenReturn(listOf(character))
        `when`(targetService.findAll(USER_ID)).thenReturn(emptyMap())
        `when`(travelerService.selection(USER_ID)).thenReturn(
            TravelerSelection(TravelerAppearance.AETHER, TravelerElement.ANEMO, false),
        )
        `when`(planningService.findCharacterState(snapshot, character.key)).thenReturn(state)
        `when`(
            planningService.calculateBalances(listOf(requirement), snapshot),
        ).thenReturn(listOf(talentBook))
        `when`(materialCraftingService.infoFor(requirement.id)).thenReturn(null)
        `when`(domainScheduleService.isTalentBookFarmable(requirement.id)).thenReturn(true)

        val dashboard = service.create(
            userId = USER_ID,
            snapshot = snapshot,
            selections = setOf(DashboardGoalSelection(character.key, DashboardGoalType.CHARACTER)),
        )

        assertEquals(FarmingActivity.TALENT_DOMAIN, dashboard.resinRecommendations.single().activity)
        assertEquals("Guide to Freedom", dashboard.resinRecommendations.single().title)
        assertTrue(dashboard.freeRecommendations.none { it.title == "Guide to Freedom" })
    }

    private fun character(): CharacterDefinition = CharacterDefinition(
        key = "testcharacter",
        id = 1,
        name = "Test Character",
        title = null,
        description = null,
        weapon = "Sword",
        rarity = 5,
        birthday = null,
        element = "Pyro",
        affiliation = null,
        region = null,
        constellation = null,
        ascensionStatType = null,
        imageUrls = emptyMap<CharacterImageType, String>(),
        remoteImageUrls = emptyMap(),
        ascensionCosts = emptyMap(),
        talentCosts = mapOf(
            7 to listOf(
                MaterialCost(
                    id = 104302,
                    name = "Guide to Freedom",
                    count = 10,
                ),
            ),
        ),
    )

    private companion object {
        private const val USER_ID = 1L
    }
}
