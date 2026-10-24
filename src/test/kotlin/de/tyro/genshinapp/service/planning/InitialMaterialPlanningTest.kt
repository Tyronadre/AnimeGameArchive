package de.tyro.genshinapp.service.planning

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.*
import de.tyro.genshinapp.service.catalog.CharacterCatalogService
import de.tyro.genshinapp.service.catalog.MaterialCatalogService
import de.tyro.genshinapp.service.importer.*
import de.tyro.genshinapp.service.player.TravelerService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitialMaterialPlanningTest {
    private val mapper = jacksonObjectMapper()
    private val characters = mock(CharacterCatalogService::class.java)
    private val materials = mock(MaterialCatalogService::class.java)
    private val source = mock(HoyolabWikiDataSource::class.java)
    private val traveler = mock(TravelerService::class.java)
    private val saved = ConcurrentHashMap<String, CharacterDefinition>()
    private val pages = HoyolabCharacterPageService(
        mapper, GenshinContentProperties().apply { hoyolabWikiEnabled = true },
        characters, materials, source,
    )
    private val planning = PlayerPlanningService(
        characters, MaterialCalculator(materials), MaterialCraftingService(materials), traveler, pages,
    )

    init {
        `when`(characters.getCharacters()).thenAnswer { saved.values.filter { it.key != "traveleranemo" } }
        `when`(characters.findExactCharacter(anyString())).thenAnswer { saved[it.getArgument<String>(0)] }
        `when`(characters.findCharacter(anyString())).thenAnswer { saved[it.getArgument<String>(0)] }
        `when`(characters.saveHoyolabEnrichment(any(CharacterDefinition::class.java) ?: definition("unused", "Unused", 0))).thenAnswer {
            val character = it.getArgument<CharacterDefinition>(0)
            saved[character.key] = character
            character
        }
        `when`(materials.canonicalCost(any(HoyolabMaterialReference::class.java) ?: HoyolabMaterialReference(164, null, 1))).thenAnswer {
            val ref = it.getArgument<HoyolabMaterialReference>(0)
            MaterialCost(104301, "Teachings of Freedom", ref.amount)
        }
        `when`(materials.getMaterialsByCategories(anyCollection())).thenReturn(emptyList())
        `when`(materials.getMaterialsByIds(anyCollection())).thenReturn(emptyList())
    }

    @AfterEach
    fun close() = pages.close()

    @Test
    fun `first plan loads costs from saved wiki ids and later plans reuse them`() {
        addSummary("amber", "Amber", 100)
        val snapshot = snapshot("Amber")

        val plan = planning.createPlan(snapshot)

        // 6 ascensions at 2 books + 3 talents with 8 upgrades at 3 books.
        assertEquals(84L, plan.aggregateMaterials.single { it.id == 104301 }.required)
        assertTrue(plan.incompleteCharacterNames.isEmpty())
        assertEquals(84L, planning.createPlan(snapshot).aggregateMaterials.single { it.id == 104301 }.required)
        verify(source, times(1)).fetchPage(100)
        verify(source, never()).search(anyString())
    }

    @Test
    fun `unowned summaries are loaded only when included in the plan`() {
        addSummary("amber", "Amber", 100)
        addSummary("kaeya", "Kaeya", 101)
        val snapshot = snapshot("Amber")
        planning.createPlan(snapshot)
        verify(source, never()).fetchPage(101)

        val plan = planning.createPlan(snapshot, includeUnownedCharacters = true)
        assertEquals(168L, plan.aggregateMaterials.single { it.id == 104301 }.required)
        verify(source, times(1)).fetchPage(101)
    }

    @Test
    fun `missing costs are loaded even for previously versioned incomplete imports`() {
        addSummary("amber", "Amber", 100)
        saved.computeIfPresent("amber") { _, value -> value.copy(hoyolabDataVersion = 2) }
        assertEquals(84L, planning.createPlan(snapshot("Amber")).aggregateMaterials.single { it.id == 104301 }.required)
        verify(source).fetchPage(100)
    }

    @Test
    fun `failed detail fetch marks totals incomplete and retries respect cooldown`() {
        addSummary("amber", "Amber", 100)
        `when`(source.fetchPage(100)).thenReturn(null)
        `when`(source.search("Amber")).thenReturn(emptyList())
        repeat(2) {
            assertEquals(listOf("Amber"), planning.createPlan(snapshot("Amber")).incompleteCharacterNames)
        }
        verify(source, times(1)).fetchPage(100)
    }

    @Test
    fun `traveler plan reloads the selected element after importing its costs`() {
        addSummary("traveler", "Traveler", 100)
        addSummary("traveleranemo", "Traveler (Anemo)", 101)
        `when`(traveler.selection(1)).thenReturn(TravelerSelection(TravelerAppearance.AETHER, TravelerElement.ANEMO, true))
        `when`(characters.findTraveler(TravelerElement.ANEMO, TravelerAppearance.AETHER)).thenAnswer {
            saved.getValue("traveler").copy(talentCosts = saved.getValue("traveleranemo").talentCosts)
        }

        val plan = planning.createPlan(snapshot("Traveler"), userId = 1)

        assertEquals(84L, plan.aggregateMaterials.single { it.id == 104301 }.required)
        assertTrue(plan.incompleteCharacterNames.isEmpty())
        verify(source).fetchPage(100)
        verify(source).fetchPage(101)
    }

    private fun addSummary(key: String, name: String, wikiId: Long) {
        saved[key] = definition(key, name, wikiId)
        val ascensions = listOf(mapOf("materials" to emptyList<Any>())) + (1..6).map {
            mapOf("materials" to listOf(mapOf("ep_id" to 164, "amount" to 2)))
        }
        val talents = listOf(emptyList<Any>()) + (2..10).map {
            listOf(mapOf("ep_id" to 164, "amount" to 3))
        }
        `when`(source.fetchPage(wikiId)).thenReturn(mapper.valueToTree(mapOf(
            "id" to wikiId, "name" to name,
            "components" to listOf(
                mapOf("component_id" to "ascension", "data" to mapOf("list" to ascensions)),
                mapOf("component_id" to "talent", "data" to mapOf("list" to listOf(
                    mapOf("title" to "Attack", "materials" to talents),
                ))),
            ),
        )))
    }

    private fun definition(key: String, name: String, wikiId: Long) = CharacterDefinition(
        key, 0, name, null, null, null, 4, null, null, null, null, null, null,
        emptyMap(), emptyMap(), emptyMap(), emptyMap(), hoyolabEntryId = wikiId,
    )

    private fun snapshot(key: String) = PlayerSnapshot(
        3, "test", Instant.EPOCH, characters = listOf(PlayerCharacterState(key, 1, 0, 0, 1, 1, 1)),
        inventory = emptyMap(), inventoryNames = emptyMap(), exportedInventoryKeys = 0,
        artifacts = emptyList(), weapons = emptyList(),
    )
}
