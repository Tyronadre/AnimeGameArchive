package de.tyro.genshinapp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.model.*
import de.tyro.genshinapp.service.catalog.*
import de.tyro.genshinapp.service.importer.*
import de.tyro.genshinapp.service.planning.*
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

@SpringBootTest(properties = [
    "spring.docker.compose.enabled=false", "spring.datasource.url=jdbc:h2:mem:source-import;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "genshin.content.hoyolab-catalog-sync-enabled=false",
    "genshin.content.cache-directory=build/test-cache/source-import",
])
@Import(HoyowikiSourceIntegrationTest.Config::class)
class HoyowikiSourceIntegrationTest @Autowired constructor(
    private val source: HoyolabWikiDataSource, private val sync: HoyolabSourceSynchronizer,
    private val catalog: HoyowikiCatalogStore, private val materials: MaterialCatalogService,
    private val pageService: MissingMaterialsPageService, private val schedules: DomainScheduleService,
    private val em: EntityManager, private val transactions: PlatformTransactionManager,
) {
    @TestConfiguration
    class Config {
        @Bean @Primary fun fakeSource(): HoyolabWikiDataSource = mock(HoyolabWikiDataSource::class.java)
    }
    private val mapper = jacksonObjectMapper()
    private val bossId = 900_000L
    private val rewardId = 900_001L
    private val synthetic = 1_500_900_001
    private fun entry(id: Long, name: String, type: String) = HoyolabWikiEntry(id, name,
        filters = mapOf("type" to listOf(type)))
    private fun page(id: Long, name: String, menu: String, type: String, refs: List<Long> = emptyList(), sourceText: String = ""): JsonNode {
        val base = mapper.writeValueAsString(mapOf("list" to listOf(
            mapOf("key" to "Type", "value" to listOf("<p>$type</p>")),
            mapOf("key" to "Region", "value" to listOf("New Area")),
            mapOf("key" to "Source", "value" to listOf(sourceText)),
        )))
        val loot = mapper.writeValueAsString(mapOf("list" to refs.map {
            "$" + mapper.writeValueAsString(listOf(mapOf("ep_id" to it, "nickname" to "错误名称"))) + "$"
        }))
        return mapper.valueToTree(mapOf("id" to id, "name" to name, "menu_id" to menu,
            "modules" to listOf(mapOf("components" to listOf(mapOf("component_id" to "baseInfo", "data" to base),
                mapOf("component_id" to "drop_material", "data" to loot))))))
    }
    private fun prepare(enemies: List<HoyolabWikiEntry>, materialEntries: List<HoyolabWikiEntry>) {
        val now = Instant.now()
        catalog.recordCatalog(HoyolabWikiMenu.ENEMIES, enemies, now, true)
        catalog.recordCatalog(HoyolabWikiMenu.MATERIALS, materialEntries, now, true)
        catalog.recordCatalog(HoyolabWikiMenu.ARTIFACTS, listOf(HoyolabWikiEntry(2095, "Gladiator's Finale")), now, true)
        materials.synchronizeHoyolabMaterialList(materialEntries.map { HoyolabMaterialSummary(it.entryPageId, it.name, null) })
    }

    @BeforeEach fun resetSource() { reset(source) }

    private fun emptyPlan(): PlayerMaterialPlan = PlayerMaterialPlan(
        PlayerSnapshot(3, "test", Instant.now(), characters = emptyList(), inventory = emptyMap(), inventoryNames = emptyMap(),
            exportedInventoryKeys = 0, artifacts = emptyList(), weapons = emptyList()), emptyList(), emptyList(), emptyList())

    @Test
    fun `generated bundle replays real source pages without network`() {
        val path = java.nio.file.Path.of("build/generated/bundled-catalog/data/bundled-catalog-snapshot.json")
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(path), "Run generateBundledCatalogSnapshot for full bundle verification")
        val bundle = java.nio.file.Files.newInputStream(path).use { mapper.readValue(it, BundledCatalogSnapshot::class.java) }
        catalog.recordCatalog(HoyolabWikiMenu.ARTIFACTS, bundle.hoyolabArtifacts, Instant.now(), true)
        catalog.recordCatalog(HoyolabWikiMenu.ENEMIES, bundle.hoyolabEnemies, Instant.now(), true)
        catalog.recordCatalog(HoyolabWikiMenu.MATERIALS, bundle.hoyolabMaterials, Instant.now(), true)
        materials.synchronizeHoyolabMaterialList(bundle.hoyolabMaterials.mapNotNull(HoyolabCatalogEntryMapper()::toMaterialSummary))
        sync.synchronize(bundled = bundle.hoyolabEnemyPages + bundle.hoyolabSourceMaterialPages)
        verifyNoInteractions(source)
        val newBoss = materials.getSources(listOf(MaterialSourceType.WEEKLY_BOSS)).single { it.hoyolabEntryId == 10781L }
        assertEquals(3, newBoss.materials.count { it.role == MaterialSourceRole.DROP })
        val world = materials.getSources(listOf(MaterialSourceType.WORLD_BOSS)).single { it.hoyolabEntryId == 8992L }
        assertTrue(world.materials.any { it.material.hoyolabEntryId == 8967L })
        val parser = HoyolabSourceParser(mapper)
        bundle.hoyolabSourceMaterialPages.filter { runCatching { parser.talent(it) }.isSuccess }.forEach {
            assertNull(catalog.sourcePage(it.path("id").asLong())?.error, "Talent page ${it.path("name").asText()} failed")
        }
    }

    @Test
    fun `offline fixtures group enemy variants without merging source rows`() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/hoyowiki-sources/11433.json")).use(mapper::readTree)
        val second = fixture.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("id", "900300").put("name", "Another Chimera")
        prepare(listOf(HoyolabWikiEntry(11433, "Bandersnatch"), HoyolabWikiEntry(900300, "Another Chimera")), listOf(
            entry(757, "Mora", "Common Currency"), entry(11370, "Chimeric Core", "Character and Weapon Enhancement Material"),
            entry(11371, "Symbiotic Chimeric Nucleus", "Character and Weapon Enhancement Material"),
            entry(11372, "Prime Chimeric Nexus", "Character and Weapon Enhancement Material")))
        sync.synchronize(bundled = listOf(fixture, second))
        verifyNoInteractions(source)
        val imported = materials.getSources(listOf(MaterialSourceType.ENEMY)).filter { it.hoyolabEntryId in setOf(11433L, 900300L) }
        assertEquals(2, imported.size)
        assertTrue(imported.all { it.materials.size == 3 })
        val cards = pageService.create(emptyPlan()).enemyDrops.filter { it.enemyName.contains("Another Chimera") }
        assertEquals(1, cards.size)
        assertTrue(cards.single().enemyName.contains("Bandersnatch"))
    }

    @Test
    fun `physical domain fallback suppresses duplicate challenge and uses published schedule`() {
        prepare(listOf(HoyolabWikiEntry(900400, "Unknown")), listOf(entry(164, "Teachings of Freedom", "Character Talent Material")))
        val book = page(164, "Teachings of Freedom", "9", "Character Talent Material",
            sourceText = "Domain of Mastery: Frosted Altar (Wednesday/Saturday/Sunday)")
        sync.synchronize(force = true, bundled = listOf(book, page(900400, "Unknown", "7", "Unknown")))
        val groups = pageService.create(emptyPlan()).talentDomains.filter { it.families.any { family -> family.materials.any { it.id == 104301 } } }
        assertEquals(1, groups.size)
        assertEquals("Forsaken Rift", groups.single().name)
        assertFalse(groups.single().challenge)
        assertTrue(schedules.isTalentBookFarmable(104301, LocalDate.of(2026, 10, 28)))
        assertFalse(schedules.isTalentBookFarmable(104301, LocalDate.of(2026, 10, 26)))
    }

    @Test
    fun `changed drops replace atomically and conflicting explicit categories keep previous links`() {
        val id = 900500L
        prepare(listOf(HoyolabWikiEntry(id, "New World Boss")), listOf(
            entry(900501, "First New Drop", "Character Level-Up Material"), entry(900502, "Second New Drop", "Character Level-Up Material")))
        doReturn(page(id, "New World Boss", "7", "Boss", listOf(900501))).`when`(source).fetchPage(id)
        sync.synchronize(force = true)
        doReturn(page(id, "New World Boss", "7", "Boss", listOf(900502))).`when`(source).fetchPage(id)
        sync.synchronize(force = true)
        fun imported() = materials.getSources(listOf(MaterialSourceType.WORLD_BOSS)).single { it.hoyolabEntryId == id }
        assertEquals(listOf("Second New Drop"), imported().materials.map { it.material.name })
        doReturn(page(id, "Wrong Weekly Type", "7", "Weekly Boss", listOf(900502))).`when`(source).fetchPage(id)
        sync.synchronize(force = true)
        assertNotNull(catalog.sourcePage(id)?.error)
        assertEquals("New World Boss", imported().name)
        assertEquals(MaterialCategory.WORLD_BOSS, imported().materials.single().material.category)
    }

    @Test
    fun `new weekly boss resolves foreign nicknames keeps shared loot out survives failures and resolves generated identity`() {
        prepare(listOf(HoyolabWikiEntry(bossId, "Unlisted Boss")), listOf(
            entry(rewardId, "Unlisted Weekly Reward", "Character Level-Up Material"), entry(757, "Mora", "Common Currency")))
        `when`(source.fetchPage(bossId)).thenReturn(page(bossId, "Unlisted Boss", "7", "Weekly Boss", listOf(rewardId, 757, 2095)))
        assertTrue(sync.synchronize(force = true) > 0)
        val boss = materials.getSources(listOf(MaterialSourceType.WEEKLY_BOSS)).single { it.hoyolabEntryId == bossId }
        assertEquals(listOf(synthetic), boss.materials.map { it.material.id })
        assertEquals("Unlisted Weekly Reward", boss.materials.single().material.name)
        assertEquals(MaterialCategory.WEEKLY_BOSS, materials.findMaterial(synthetic)?.category)
        val snapshot = PlayerSnapshot(3, "test", Instant.now(), characters = emptyList(), inventory = emptyMap(),
            inventoryNames = emptyMap(), exportedInventoryKeys = 0, artifacts = emptyList(), weapons = emptyList())
        val website = pageService.create(PlayerMaterialPlan(snapshot, emptyList(), emptyList(), emptyList()))
        assertEquals(0L, website.weeklyBosses.single { it.name == "Unlisted Boss" }.drops.single().owned)

        `when`(source.fetchPage(bossId)).thenThrow(IllegalStateException("offline"))
        sync.synchronize(force = true)
        assertNotNull(catalog.sourcePage(bossId)?.error)
        assertEquals(1, materials.getSources(listOf(MaterialSourceType.WEEKLY_BOSS)).single { it.hoyolabEntryId == bossId }.materials.size)
        doReturn(page(bossId, "Renamed Boss", "7", "Weekly Boss", listOf(rewardId))).`when`(source).fetchPage(bossId)
        sync.synchronize()
        assertNull(catalog.sourcePage(bossId)?.error)
        assertEquals(boss.key, materials.getSources(listOf(MaterialSourceType.WEEKLY_BOSS)).single { it.hoyolabEntryId == bossId }.key)
        materials.synchronizeCharacters(emptyList())
        assertEquals(MaterialCategory.WEEKLY_BOSS, materials.findMaterial(synthetic)?.category)

        val identities = mock(MaterialIdentityCatalog::class.java)
        `when`(identities.gameIdForName("Unlisted Weekly Reward")).thenReturn(113999)
        MaterialIdentityRepair(em, identities, transactions).repair()
        materials.reloadFromDatabase()
        val repaired = materials.getSources(listOf(MaterialSourceType.WEEKLY_BOSS)).single { it.hoyolabEntryId == bossId }
        assertEquals(113999, repaired.materials.single().material.id)
        assertEquals("HOYOWIKI", repaired.materials.single().material.categoryOrigin)
    }

    @Test
    fun `new talent challenge uses explicit schedule for generated ids and offline cached parser replay`() {
        val id = 900_100L
        prepare(listOf(HoyolabWikiEntry(900_101, "Unknown Enemy")),
            listOf(entry(id, "Teachings of Tomorrow", "Character Talent Material")))
        val detail = page(id, "Teachings of Tomorrow", "9", "Character Talent Material",
            sourceText = "Domain of Mastery: Future Hall (Tuesday/Friday/Sunday)")
        `when`(source.fetchPage(id)).thenReturn(detail)
        `when`(source.fetchPage(900_101)).thenReturn(page(900_101, "Unknown Enemy", "7", "Unknown Type"))
        sync.synchronize(force = true)
        val challenge = materials.getSources(listOf(MaterialSourceType.TALENT_DOMAIN)).single { it.name == "Future Hall" }
        val materialId = challenge.materials.single().material.id
        assertTrue(materialId >= 1_500_000_000)
        assertTrue(schedules.isTalentBookFarmable(materialId, LocalDate.of(2026, 10, 27)))
        assertFalse(schedules.isTalentBookFarmable(materialId, LocalDate.of(2026, 10, 26)))
        clearInvocations(source)
        org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult {
            em.find(de.tyro.genshinapp.entity.HoyowikiEntry::class.java, id).sourceParserVersion = 0
        }
        sync.synchronize(bundled = emptyList())
        verifyNoInteractions(source)
        assertEquals(HoyolabSourceParser.VERSION, catalog.sourcePage(id)?.parserVersion)
        assertNotNull(catalog.sourcePage(900_101)?.error)
        assertEquals(1, materials.getSources(listOf(MaterialSourceType.TALENT_DOMAIN)).count { it.name == "Future Hall" })
    }
}
