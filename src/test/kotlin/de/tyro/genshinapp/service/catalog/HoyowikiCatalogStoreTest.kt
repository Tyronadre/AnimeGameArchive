package de.tyro.genshinapp.service.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.entity.*
import de.tyro.genshinapp.service.importer.HoyolabWikiEntry
import de.tyro.genshinapp.service.importer.HoyolabWikiMenu
import jakarta.persistence.EntityManager
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.time.Instant
import kotlin.test.*

@DataJpaTest(properties = ["spring.jpa.show-sql=false", "spring.docker.compose.enabled=false"])
@Import(HoyowikiCatalogStore::class, HoyowikiCatalogStoreTest.Config::class)
class HoyowikiCatalogStoreTest @Autowired constructor(
    private val store: HoyowikiCatalogStore,
    private val entityManager: EntityManager,
) {
    @TestConfiguration
    class Config {
        @Bean fun objectMapper(): ObjectMapper = jacksonObjectMapper()
        @Bean fun identityCatalog(): MaterialIdentityCatalog = mock(MaterialIdentityCatalog::class.java).also {
            `when`(it.gameIdForName("Teachings of Freedom")).thenReturn(104301)
        }
    }

    @Test
    fun `wiki identity links canonical material and survives a restart without conflating IDs`() {
        val canonical = Material().apply { gameId = 104301; name = "Teachings of Freedom" }
        val legacy = Material().apply { gameId = 164; name = "Teachings of Freedom" }
        entityManager.persist(canonical)
        entityManager.persist(legacy)
        val generated = Instant.parse("2026-10-01T00:00:00Z")
        val entry = HoyolabWikiEntry(164, canonical.name)
        store.recordCatalog(HoyolabWikiMenu.MATERIALS, listOf(entry), generated, live = false)
        entityManager.flush()
        entityManager.clear()
        val material = entityManager.find(Material::class.java, canonical.id)
        assertEquals(104301, material.gameId)
        assertEquals(164L, material.hoyowikiEntry?.pageId)
        assertNull(material.hoyowikiEntry?.lastQueriedAt)
        assertNull(entityManager.find(Material::class.java, legacy.id).hoyowikiEntry)
        val queried = generated.plusSeconds(60)
        store.recordCatalog(HoyolabWikiMenu.MATERIALS, listOf(entry), queried, live = true)
        store.recordDetailQuery(164, jacksonObjectMapper().readTree("""{"id":"164","name":"Teachings of Freedom"}"""), queried.plusSeconds(10))
        entityManager.flush()
        entityManager.clear()
        val wiki = entityManager.find(HoyowikiEntry::class.java, 164L)
        assertEquals(queried.plusSeconds(10), wiki.lastQueriedAt)
        assertEquals(queried.plusSeconds(10), wiki.lastDetailQueriedAt)
        assertNotNull(wiki.detailJson)
        assertEquals(listOf(entry), store.entries(HoyolabWikiMenu.MATERIALS))
        assertEquals(queried, store.checkpoint(HoyolabWikiMenu.MATERIALS)?.lastFullSyncAt)
        // Reapplying an older bundle must not roll back the live checkpoint or query times.
        store.recordCatalog(HoyolabWikiMenu.MATERIALS, listOf(entry), generated, live = false)
        assertEquals(queried, store.checkpoint(HoyolabWikiMenu.MATERIALS)?.lastFullSyncAt)
    }

    @Test
    fun `characters weapons and artifacts retain wiki relationships`() {
        val character = GameCharacter().apply { key = "testcharacter"; name = "Test character"; hoyolabEntryId = 10 }
        val weapon = GameWeapon().apply { key = "testweapon"; name = "Test weapon"; hoyolabEntryId = 20 }
        val bonus = ArtifactSetBonus().apply { setKey = "testset"; setName = "Test set" }
        entityManager.persist(character); entityManager.persist(weapon); entityManager.persist(bonus)
        val now = Instant.now()
        store.recordCatalog(HoyolabWikiMenu.CHARACTERS, listOf(HoyolabWikiEntry(10, character.name)), now, true)
        store.recordCatalog(HoyolabWikiMenu.WEAPONS, listOf(HoyolabWikiEntry(20, weapon.name)), now, true)
        val artifact = HoyolabWikiEntry(30, "Test set", displayFields = mapOf(
            "flower_of_life_icon_url" to "https://bbs.hoyolab.com/flower.png"))
        store.recordCatalog(HoyolabWikiMenu.ARTIFACTS, listOf(artifact), now, true)
        store.markBundledVersion("test-snapshot")
        entityManager.flush(); entityManager.clear()
        assertEquals(10L, entityManager.find(GameCharacter::class.java, character.id).hoyowikiEntry?.pageId)
        assertEquals(20L, entityManager.find(GameWeapon::class.java, weapon.id).hoyowikiEntry?.pageId)
        assertEquals(30L, entityManager.find(GameArtifactSet::class.java, "testset").hoyowikiEntry?.pageId)
        assertEquals(30L, entityManager.find(ArtifactSetBonus::class.java, bonus.id).hoyowikiEntry?.pageId)
        assertTrue(store.hasBundledVersion("test-snapshot"))
        val reloaded = ArtifactCatalogService(jacksonObjectMapper(), store)
        assertEquals("Test set", reloaded.setName("testset"))
        assertEquals("https://bbs.hoyolab.com/flower.png", reloaded.artifactImage("testset", "flower")?.remoteUrl)
    }
}
