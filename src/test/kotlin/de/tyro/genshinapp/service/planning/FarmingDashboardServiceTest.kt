package de.tyro.genshinapp.service.planning

import de.tyro.genshinapp.service.artifact.ArtifactOptimizationService
import de.tyro.genshinapp.service.artifact.ArtifactOptimizerProfileService
import de.tyro.genshinapp.service.catalog.ArtifactCatalogService
import de.tyro.genshinapp.service.catalog.CharacterCatalogService
import de.tyro.genshinapp.service.catalog.MaterialCatalogService
import de.tyro.genshinapp.service.catalog.WeaponDataService
import de.tyro.genshinapp.service.player.TravelerService
import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterImageType
import de.tyro.genshinapp.model.InventoryMaterialBalance
import de.tyro.genshinapp.model.MaterialCost
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialRequirement
import de.tyro.genshinapp.model.MaterialDefinition
import de.tyro.genshinapp.model.MaterialSourceDefinition
import de.tyro.genshinapp.model.MaterialSourceMaterialDefinition
import de.tyro.genshinapp.model.MaterialSourceRole
import de.tyro.genshinapp.model.MaterialSourceType
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

    @Test
    fun `collected boss drops are hidden but other bosses and shared gems remain available`() {
        val drops = listOf(
            MaterialDefinition(113068, "Eroded Horn", MaterialCategory.WEEKLY_BOSS),
            MaterialDefinition(113069, "Eroded Sunfire", MaterialCategory.WEEKLY_BOSS),
            MaterialDefinition(113070, "Eroded Scale-Feather", MaterialCategory.WEEKLY_BOSS),
            MaterialDefinition(113003, "Dvalin's Plume", MaterialCategory.WEEKLY_BOSS),
            MaterialDefinition(104111, "Agnidus Agate Sliver", MaterialCategory.GEM),
        )
        val character = character().copy(talentCosts = mapOf(
            7 to drops.map { MaterialCost(it.id, it.name, 10) },
        ))
        val snapshot = PlayerSnapshot(
            formatVersion = 3, source = "Irminsul", importedAt = Instant.EPOCH,
            characters = emptyList(), inventory = emptyMap(), inventoryNames = emptyMap(),
            exportedInventoryKeys = 0, artifacts = emptyList(), weapons = emptyList(),
        )
        val requirements = drops.sortedBy { it.id }.map { MaterialRequirement(it.id, it.name, 30, null) }
        val balances = drops.map {
            InventoryMaterialBalance(
                id = it.id, name = it.name, required = 30, owned = 0, missing = 30,
                imageUrl = null, category = it.category,
            )
        }
        `when`(catalogService.getCharacters()).thenReturn(listOf(character))
        `when`(targetService.findAll(USER_ID)).thenReturn(emptyMap())
        `when`(travelerService.selection(USER_ID)).thenReturn(
            TravelerSelection(TravelerAppearance.AETHER, TravelerElement.ANEMO, false),
        )
        `when`(planningService.findCharacterState(snapshot, character.key)).thenReturn(
            PlayerCharacterState(character.key, 80, 0, 6, 6, 6, 6),
        )
        `when`(planningService.calculateBalances(requirements, snapshot)).thenReturn(balances)
        `when`(materialCatalogService.getSources(listOf(MaterialSourceType.WEEKLY_BOSS))).thenReturn(listOf(
            MaterialSourceDefinition(
                "weekly_boss:lordoferodedprimalfire", "Lord of Eroded Primal Fire", MaterialSourceType.WEEKLY_BOSS,
                materials = drops.take(3).map { MaterialSourceMaterialDefinition(it, MaterialSourceRole.DROP) } +
                    MaterialSourceMaterialDefinition(drops.last(), MaterialSourceRole.GEM),
            ),
            MaterialSourceDefinition(
                "weekly_boss:stormterror", "Stormterror", MaterialSourceType.WEEKLY_BOSS,
                materials = listOf(MaterialSourceMaterialDefinition(drops[3], MaterialSourceRole.DROP)),
            ),
        ))

        for (automatic in listOf(false, true)) {
            fun dashboard(collected: Set<String>) = service.create(
                USER_ID, snapshot, setOf(DashboardGoalSelection(character.key, DashboardGoalType.CHARACTER)),
                automaticPlan = automatic, collectedWeeklyBossKeys = collected,
            )
            // Anonymous manual claims never supply a boss identity to the planner.
            assertEquals(2, dashboard(emptySet()).weeklyRecommendations.size)
            val collected = dashboard(setOf("lordoferodedprimalfire"))
            assertEquals(listOf("Stormterror"), collected.weeklyRecommendations.map { it.title })
            assertEquals(listOf("Agnidus Agate Sliver"), collected.resinRecommendations.map { it.title })
            assertEquals(2, dashboard(setOf("unknownboss")).weeklyRecommendations.size)
            // The same cached inventory must be usable again when the new week's claims are empty.
            assertEquals(2, dashboard(emptySet()).weeklyRecommendations.size)
        }
        val gemBoss = MaterialSourceDefinition(
            "weekly_boss:gem", "Gem boss", MaterialSourceType.WEEKLY_BOSS,
            materials = listOf(MaterialSourceMaterialDefinition(drops.last(), MaterialSourceRole.GEM)),
        )
        val stockBosses = (1..3).map {
            MaterialSourceDefinition("stock:$it", "Stock $it", MaterialSourceType.WEEKLY_BOSS)
        }
        `when`(materialCatalogService.getSources(listOf(MaterialSourceType.WEEKLY_BOSS)))
            .thenReturn(stockBosses + gemBoss)
        val fallback = service.create(
            USER_ID, snapshot, setOf(DashboardGoalSelection(character.key, DashboardGoalType.CHARACTER)),
            automaticPlan = true,
        ).weeklyRecommendations
        assertEquals(listOf("Gem boss", "Stock 1", "Stock 2"), fallback.map { it.title })
        assertEquals(listOf(104111), fallback.first().materials.map { it.id })
    }

    @Test
    fun `stock bosses fill open claims and collected bosses are excluded`() {
        `when`(catalogService.getCharacters()).thenReturn(emptyList())
        `when`(targetService.findAll(USER_ID)).thenReturn(emptyMap())
        `when`(travelerService.selection(USER_ID)).thenReturn(
            TravelerSelection(TravelerAppearance.AETHER, TravelerElement.ANEMO, false),
        )
        `when`(materialCatalogService.getSources(listOf(MaterialSourceType.WEEKLY_BOSS))).thenReturn(
            (1..4).map { MaterialSourceDefinition("boss:$it", "Boss $it", MaterialSourceType.WEEKLY_BOSS) },
        )
        val snapshot = PlayerSnapshot(
            formatVersion = 3, source = "test", importedAt = Instant.EPOCH,
            characters = emptyList(), inventory = emptyMap(), inventoryNames = emptyMap(),
            exportedInventoryKeys = 0, artifacts = emptyList(), weapons = emptyList(),
        )
        fun weekly(claims: Set<String>) = service.create(
            USER_ID, snapshot, emptySet(), collectedWeeklyBossKeys = claims,
        ).weeklyRecommendations
        assertEquals(3, weekly(emptySet()).size)
        assertEquals(listOf("Boss 2", "Boss 3"), weekly(setOf("boss1")).map { it.title })
        assertTrue(weekly(setOf("boss1", "boss2", "boss3")).isEmpty())
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
