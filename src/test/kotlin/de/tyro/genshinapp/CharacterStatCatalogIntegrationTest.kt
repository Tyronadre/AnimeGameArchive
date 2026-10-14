package de.tyro.genshinapp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.FightProperty.*
import de.tyro.genshinapp.service.catalog.*
import de.tyro.genshinapp.service.content.DynamicContentLoader
import de.tyro.genshinapp.service.content.FandomImageUrlResolver
import de.tyro.genshinapp.service.importer.*
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.times
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.doThrow
import org.mockito.ArgumentMatchers.anyCollection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import kotlin.test.*

@DataJpaTest(showSql = false)
@Import(JpaCharacterCatalogStore::class)
class CharacterStatCatalogIntegrationTest @Autowired constructor(
    private val store: CharacterCatalogStore,
    private val entityManager: EntityManager,
    private val jdbc: JdbcTemplate,
) {
    private val mapper = jacksonObjectMapper()
    private val properties = GenshinContentProperties()
    private val characters = CharacterCatalogService(mock(DynamicContentLoader::class.java), FandomImageUrlResolver(properties), store)
    private val importer = HoyolabCharacterPageService(mapper, properties, characters, mock(MaterialCatalogService::class.java))
    private val catalog = CharacterStatCatalog(store)

    @Test
    fun `Lauma imports nonzero bases curves and independent growth and ascension into database`() {
        import("lauma")
        entityManager.flush()
        entityManager.clear()
        val stored = assertNotNull(store.findCharacter("lauma"))
        val profile = assertNotNull(stored.stats)
        assertEquals(mapOf(FIGHT_PROP_BASE_HP to 829.0, FIGHT_PROP_BASE_ATTACK to 20.0,
            FIGHT_PROP_BASE_DEFENSE to 52.0, FIGHT_PROP_ELEMENT_MASTERY to 200.0), profile.baseStats)
        assertEquals("GROW_CURVE_HP_S5", profile.growthCurves[FIGHT_PROP_BASE_HP])
        assertEquals("GROW_CURVE_ATTACK_S5", profile.growthCurves[FIGHT_PROP_BASE_ATTACK])
        assertEquals("GROW_CURVE_HP_S5", profile.growthCurves[FIGHT_PROP_BASE_DEFENSE])
        assertEquals(4, count("game_character_base_stat"))
        assertEquals(21, count("game_character_level_stat"))
        assertEquals(24, count("game_character_ascension_stat"))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM game_character_base_stat WHERE stat_value = 0", Int::class.java))
        assertEquals(115.0, profile.ascensionBonuses.getValue(6)[FIGHT_PROP_ELEMENT_MASTERY])

        val checkpoints = listOf(
            listOf(1, 0, 829, 20, 52, 200),
            listOf(20, 0, 2151, 51, 135, 200), listOf(20, 1, 2863, 69, 180, 200),
            listOf(40, 1, 4283, 103, 269, 200), listOf(40, 2, 4789, 115, 301, 229),
            listOf(50, 2, 5509, 132, 346, 229), listOf(50, 3, 6183, 148, 388, 258),
            listOf(60, 3, 6911, 165, 434, 258), listOf(60, 4, 7416, 177, 465, 258),
            listOf(70, 4, 8151, 195, 512, 258), listOf(70, 5, 8657, 207, 543, 286),
            listOf(80, 5, 9400, 225, 590, 286), listOf(80, 6, 9905, 237, 622, 315),
            listOf(90, 6, 10654, 255, 669, 315),
        )
        checkpoints.forEach { row ->
            val stats = assertNotNull(catalog.resolve("Lauma", row[0], row[1]))
            listOf("hp", "atk", "def", "eleMas").forEachIndexed { i, key ->
                assertEquals(row[i + 2].toDouble(), stats[key], "level ${row[0]} phase ${row[1]} $key")
            }
            assertEquals(5.0, stats["critRate_"])
            assertEquals(50.0, stats["critDMG_"])
            assertEquals(100.0, stats["enerRech_"])
        }
        val middle = assertNotNull(catalog.resolve("Lauma", 35, 1))
        assertEquals(2863 + (4283 - 2863) * (3.875 - 2.594) / (4.307 - 2.594), middle.getValue("hp"), 1e-8)
        assertEquals(200.0, middle["eleMas"])
        assertNotEquals(2863 + (4283 - 2863) * 15.0 / 20, middle["hp"])
        for ((level, phase) in listOf(0 to 0, 91 to 6, 19 to 1, 21 to 0, 81 to 5, 90 to -1, 90 to 7)) {
            assertNull(catalog.resolve("Lauma", level, phase))
        }
        assertNull(catalog.resolve("missing", 90, 6))
    }

    @Test
    fun `reimport updates existing rows and removes base values that become zero`() {
        import("lauma")
        entityManager.flush()
        val originalIds = jdbc.queryForList("SELECT id FROM game_character_level_stat ORDER BY id", Long::class.java)
        import("lauma")
        entityManager.flush()
        assertEquals(originalIds, jdbc.queryForList("SELECT id FROM game_character_level_stat ORDER BY id", Long::class.java))
        assertEquals(4, count("game_character_base_stat"))

        val page = page("lauma")
        val component = component(page)
        val data = mapper.readTree(component.path("data").asText())
        data.path("list").forEach { row ->
            val values = row.path("combatList").last().path("values") as ArrayNode
            for (i in 0..1) {
                values[i].asText().toDoubleOrNull()?.let { values.set(i, mapper.nodeFactory.textNode((it - 200).toInt().toString())) }
            }
        }
        component.put("data", mapper.writeValueAsString(data))
        forceImport(page)
        entityManager.flush()
        entityManager.clear()
        assertEquals(3, count("game_character_base_stat"))
        assertFalse(assertNotNull(store.findCharacter("lauma")?.stats).baseStats.containsKey(FIGHT_PROP_ELEMENT_MASTERY))
        assertEquals(0.0, catalog.resolve("lauma", 1, 0)?.get("eleMas"))
        assertEquals(115.0, catalog.resolve("lauma", 90, 6)?.get("eleMas"))
    }

    @Test
    fun `crit ascension starts at zero and is added to universal defaults exactly once`() {
        import("nefer")
        entityManager.flush()
        entityManager.clear()
        assertEquals(4, count("game_character_base_stat"))
        val first = assertNotNull(catalog.resolve("nefer", 1, 0))
        val last = assertNotNull(catalog.resolve("nefer", 90, 6))
        assertEquals(50.0, first["critDMG_"])
        assertEquals(88.4, last.getValue("critDMG_"), 1e-8)
        assertEquals(12704.0, last["hp"])
        assertEquals(344.0, last["atk"])
        assertEquals(100.0, first["eleMas"])
        assertEquals(100.0, last["eleMas"])
    }

    @Test
    fun `an explicit nonzero crit base replaces the universal default`() {
        import("nefer")
        val page = page("nefer")
        val component = component(page)
        val data = mapper.readTree(component.path("data").asText())
        data.path("list").forEach { row ->
            val secondary = row.path("combatList").last() as ObjectNode
            secondary.put("key", "CRIT DMG")
            val values = secondary.path("values") as ArrayNode
            for (i in 0..1) {
                values[i].asText().removeSuffix("%").toDoubleOrNull()?.let {
                    values.set(i, mapper.nodeFactory.textNode("${it + 50}%"))
                }
            }
        }
        component.put("data", mapper.writeValueAsString(data))
        forceImport(page)
        entityManager.flush()
        entityManager.clear()
        assertEquals(5, count("game_character_base_stat"))
        assertEquals(50.0, catalog.find("nefer")?.baseStats?.get(FIGHT_PROP_CRITICAL_HURT))
        assertEquals(50.0, catalog.resolve("nefer", 1, 0)?.get("critDMG_"))
        assertEquals(88.4, catalog.resolve("nefer", 90, 6)?.get("critDMG_"))
    }

    @Test
    fun `curve inference uses observed growth including five star Traveler and aliases`() {
        import("amber")
        import("traveler")
        for (key in listOf("amber", "traveler", "Aether", "Lumine", "TravelerDendro")) {
            assertEquals("GROW_CURVE_HP_S4", catalog.find(key)?.growthCurves?.get(FIGHT_PROP_BASE_HP))
        }
        assertEquals(9461.0, catalog.resolve("amber", 90, 6)?.get("hp"))
        assertEquals(10875.0, catalog.resolve("Lumine", 90, 6)?.get("hp"))
        assertEquals(24.0, catalog.resolve("Aether", 90, 6)?.get("atk_"))
    }

    @Test
    fun `incomplete import keeps usable stats and shuffled combat rows keep their meaning`() {
        val original = import("lauma")
        val page = page("lauma")
        val component = component(page)
        val data = mapper.readTree(component.path("data").asText())
        data.path("list").forEach { row ->
            val combat = row.path("combatList") as ArrayNode
            val reversed = combat.toList().reversed()
            combat.removeAll().addAll(reversed)
        }
        component.put("data", mapper.writeValueAsString(data))
        assertEquals(original.stats, forceImport(page)?.stats)
        (data.path("list") as ArrayNode).remove(2)
        component.put("data", mapper.writeValueAsString(data))
        assertEquals(original.stats, forceImport(page)?.stats)
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    fun `legacy database rows migrate offline and are not reimported over normalized stats`() {
        val original = import("nefer")
        entityManager.flush()
        jdbc.execute("""
            CREATE TABLE game_character_progression (
                character_id BIGINT, character_level INT,
                hp_before_ascension DOUBLE, hp_after_ascension DOUBLE,
                attack_before_ascension DOUBLE, attack_after_ascension DOUBLE,
                defense_before_ascension DOUBLE, defense_after_ascension DOUBLE,
                secondary_stat_before_ascension DOUBLE, secondary_stat_after_ascension DOUBLE
            )
        """.trimIndent())
        try {
            val id = jdbc.queryForObject("SELECT id FROM game_character WHERE catalog_key = 'nefer'", Long::class.java)
            val rows = mapper.readTree(component(page("nefer")).path("data").asText()).path("list")
            rows.forEach { row ->
                val values = row.path("combatList").drop(1).flatMap { it.path("values").map { it.asText().removeSuffix("%").toDoubleOrNull() } }
                jdbc.update("INSERT INTO game_character_progression VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, row.path("key").asText().removePrefix("Lv.").toInt(), *values.toTypedArray())
            }
            jdbc.update("DELETE FROM game_character_base_stat")
            jdbc.update("DELETE FROM game_character_level_stat")
            jdbc.update("DELETE FROM game_character_ascension_stat")
            entityManager.clear()
            val migration = CharacterStatStorageMigration(jdbc, characters, mapper)
            migration.run(DefaultApplicationArguments())
            entityManager.flush()
            entityManager.clear()
            assertEquals(original.stats, store.findCharacter("nefer")?.stats)
            val ids = jdbc.queryForList("SELECT id FROM game_character_base_stat ORDER BY id", Long::class.java)
            migration.run(DefaultApplicationArguments())
            entityManager.flush()
            assertEquals(ids, jdbc.queryForList("SELECT id FROM game_character_base_stat ORDER BY id", Long::class.java))
            assertEquals(88.4, catalog.resolve("nefer", 90, 6)?.get("critDMG_"))
        } finally {
            jdbc.execute("DROP TABLE game_character_progression")
        }
    }

    @Test
    fun `completed page imports preserve manual stats across service restarts and version changes`() {
        val original = import("lauma")
        val stats = assertNotNull(original.stats)
        characters.saveHoyolabEnrichment(original.copy(hoyolabDataVersion = 1,
            stats = stats.copy(baseStats = stats.baseStats + (FIGHT_PROP_ELEMENT_MASTERY to 250.0))))
        entityManager.flush()
        entityManager.clear()
        val restarted = CharacterCatalogService(mock(DynamicContentLoader::class.java), FandomImageUrlResolver(properties), store)
        val source = mock(HoyolabWikiDataSource::class.java)
        val pages = HoyolabCharacterPageService(mapper, properties, restarted, mock(MaterialCatalogService::class.java), source)
        assertEquals(250.0, pages.importPage(page("lauma"))?.stats?.baseStats?.get(FIGHT_PROP_ELEMENT_MASTERY))
        assertEquals(250.0, pages.enrichExact("lauma")?.stats?.baseStats?.get(FIGHT_PROP_ELEMENT_MASTERY))
        verifyNoInteractions(source)
    }

    @Test
    fun `startup option reruns saved characters once and leaves normal requests using saved values`() {
        val original = import("lauma")
        val stats = assertNotNull(original.stats)
        characters.saveHoyolabEnrichment(original.copy(
            stats = stats.copy(baseStats = stats.baseStats + (FIGHT_PROP_ELEMENT_MASTERY to 250.0))))
        val source = mock(HoyolabWikiDataSource::class.java)
        `when`(source.fetchPage(assertNotNull(original.hoyolabEntryId))).thenReturn(page("lauma"))
        val pages = HoyolabCharacterPageService(mapper, properties, characters, mock(MaterialCatalogService::class.java), source)
        val rerunner = HoyolabCharacterImportRerunner(properties, store, pages)
        rerunner.run(DefaultApplicationArguments())
        verifyNoInteractions(source)
        properties.rerunCharacterImports = true
        rerunner.run(DefaultApplicationArguments())
        rerunner.run(DefaultApplicationArguments())
        pages.enrichExact("lauma")
        verify(source, times(1)).fetchPage(assertNotNull(original.hoyolabEntryId))
        assertEquals(200.0, catalog.resolve("lauma", 1, 0)?.get("eleMas"))
    }

    @Test
    fun `a bundled rerun does not trigger another live import at startup`() {
        import("lauma")
        properties.rerunCharacterImports = true
        val source = mock(HoyolabWikiDataSource::class.java)
        val pages = HoyolabCharacterPageService(mapper, properties, characters, mock(MaterialCatalogService::class.java), source)
        pages.importPage(page("lauma"), force = properties.rerunCharacterImports)
        HoyolabCharacterImportRerunner(properties, store, pages).run(DefaultApplicationArguments())
        verifyNoInteractions(source)
    }

    @Test
    fun `bare ascension materials resolve using named talent references before costs are converted`() {
        import("lauma")
        val page = page("lauma")
        val component = component(page)
        val data = mapper.readTree(component.path("data").asText())
        val materials = (data.path("list")[1] as ObjectNode).putArray("materials")
        materials.addObject().put("ep_id", 447).put("amount", 3)
        materials.addObject().put("ep_id", 448).put("amount", 2)
        component.put("data", mapper.writeValueAsString(data))
        val talent = mapper.readTree("""{
            "list": [{"title": "Test Talent", "attributes": [{"key": "DMG", "values": ["100%"]}],
                "materials": [[], [{"ep_id":447,"name":"Slime Condensate","amount":4},
                    {"ep_id":448,"amount":5}]]}]
        }""")
        (page.path("modules")[0].path("components") as ArrayNode).addObject()
            .put("component_id", "talent").set<JsonNode>("data", talent)
        val catalog = MaterialCatalogService(identityCatalog = MaterialIdentityCatalog(mapper, properties))
        val pages = HoyolabCharacterPageService(mapper, properties, characters, catalog)
        val imported = assertNotNull(pages.importPage(page, force = true))
        entityManager.flush()
        entityManager.clear()
        val saved = assertNotNull(store.findCharacter("lauma"))
        assertEquals(imported.ascensionCosts, saved.ascensionCosts)
        val ascension = saved.ascensionCosts.getValue(1)
        assertEquals(112002, ascension[0].id)
        assertEquals("Slime Condensate", ascension[0].name)
        assertEquals(3, ascension[0].count)
        assertEquals("HoYoWiki material 448", ascension[1].name)
        assertEquals(2, ascension[1].count)
        val costs = saved.talentCosts.getValue(2)
        assertEquals(112002, costs[0].id)
        assertEquals(4, costs[0].count)
        assertEquals(ascension[1].id, costs[1].id)
        assertEquals(5, costs[1].count)
    }

    @Test
    fun `a failed live import preserves saved data and does not mark the rerun complete`() {
        val original = import("lauma")
        val source = mock(HoyolabWikiDataSource::class.java)
        `when`(source.fetchPage(assertNotNull(original.hoyolabEntryId))).thenReturn(page("lauma"))
        val materials = mock(MaterialCatalogService::class.java)
        doThrow(IllegalArgumentException("Unresolved material")).`when`(materials)
            .synchronizeHoyolabReferences(anyCollection())
        val pages = HoyolabCharacterPageService(mapper, properties, characters, materials, source)
        assertEquals(original, pages.enrichExact("lauma", force = true))
        assertEquals(original, pages.enrichExact("lauma", force = true))
        verify(source, times(1)).fetchPage(assertNotNull(original.hoyolabEntryId))
        assertEquals(original.stats, store.findCharacter("lauma")?.stats)
    }

    private fun forceImport(page: JsonNode): CharacterDefinition? = HoyolabCharacterPageService(
        mapper, properties, characters, mock(MaterialCatalogService::class.java),
    ).importPage(page, force = true)

    @Test
    fun `Nefer correction backfills existing records once preserves manual values and survives reruns`() {
        val original = import("nefer")
        val profile = assertNotNull(original.stats)
        characters.saveHoyolabEnrichment(original.copy(
            stats = profile.copy(baseStats = profile.baseStats - FIGHT_PROP_ELEMENT_MASTERY),
        ))
        entityManager.flush()
        entityManager.clear()
        assertEquals(0.0, catalog.resolve("nefer", 1, 0)?.get("eleMas"))
        val correctionImporter = CharacterBaseStatCorrectionImporter(store, characters)
        correctionImporter.run(DefaultApplicationArguments())
        entityManager.flush()
        entityManager.clear()
        assertEquals(100.0, catalog.resolve("nefer", 1, 0)?.get("eleMas"))
        assertEquals(100.0, catalog.resolve("nefer", 90, 6)?.get("eleMas"))
        assertEquals(original.hoyolabDataVersion, store.findCharacter("nefer")?.hoyolabDataVersion)
        val ids = jdbc.queryForList("SELECT id FROM game_character_base_stat ORDER BY id", Long::class.java)
        correctionImporter.run(DefaultApplicationArguments())
        entityManager.flush()
        assertEquals(ids, jdbc.queryForList("SELECT id FROM game_character_base_stat ORDER BY id", Long::class.java))

        val saved = assertNotNull(store.findCharacter("nefer"))
        val savedProfile = assertNotNull(saved.stats)
        characters.saveHoyolabEnrichment(saved.copy(stats = savedProfile.copy(
            baseStats = savedProfile.baseStats + (FIGHT_PROP_ELEMENT_MASTERY to 125.0),
        )))
        correctionImporter.run(DefaultApplicationArguments())
        assertEquals(125.0, catalog.resolve("nefer", 90, 6)?.get("eleMas"))
        forceImport(page("nefer"))
        assertEquals(100.0, catalog.resolve("nefer", 90, 6)?.get("eleMas"))
        assertEquals(4, count("game_character_base_stat"))
    }

    private fun import(key: String): CharacterDefinition {
        val page = page(key)
        characters.synchronizeHoyolabCharacterList(listOf(HoyolabCharacterSummary(
            page.path("id").asLong(), page.path("name").asText(), rarity = if (key == "amber") 4 else 5,
            ascensionStat = when (key) {
                "lauma" -> FIGHT_PROP_ELEMENT_MASTERY
                "nefer" -> FIGHT_PROP_CRITICAL_HURT
                else -> FIGHT_PROP_ATTACK_PERCENT
            },
        )))
        return assertNotNull(importer.importPage(page))
    }

    private fun page(key: String): JsonNode = ClassPathResource("hoyolab/characters/$key.json").inputStream.use(mapper::readTree)
    private fun component(page: JsonNode) = page.path("modules")[0].path("components")[0] as ObjectNode
    private fun count(table: String) = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)
}
