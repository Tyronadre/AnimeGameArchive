package de.tyro.genshinapp.service.importer

import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.service.catalog.*
import org.mockito.Mockito.*
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HoyolabCatalogStartupSynchronizerTest {
    private val source = mock(HoyolabWikiDataSource::class.java)
    private val materials = mock(MaterialCatalogService::class.java)
    private val characters = mock(CharacterCatalogService::class.java)
    private val weapons = mock(WeaponCatalogService::class.java)
    private val artifacts = mock(ArtifactCatalogService::class.java)
    private val store = mock(HoyowikiCatalogStore::class.java)
    private val service = HoyolabCatalogStartupSynchronizer(source, GenshinContentProperties(),
        characters, weapons, artifacts, materials, HoyolabCatalogEntryMapper(), store)
    private val menus = listOf(HoyolabWikiMenu.MATERIALS, HoyolabWikiMenu.CHARACTERS,
        HoyolabWikiMenu.WEAPONS, HoyolabWikiMenu.ARTIFACTS)

    private fun recordedMenus() = mockingDetails(store).invocations
        .filter { it.method.name == "recordCatalog" }.map { it.arguments[0] }

    private fun unchangedCatalogs() {
        menus.forEach {
            `when`(source.probeCatalog(it)).thenReturn(HoyolabCatalogProbe(1, 1))
            `when`(store.checkpoint(it)).thenReturn(HoyowikiCatalogCheckpoint(1, 1, Instant.now()))
        }
    }

    @Test
    fun `unchanged catalogs only use probes`() {
        unchangedCatalogs()
        assertEquals(0, service.synchronizeNow())
        menus.forEach { verify(source).probeCatalog(it); verify(source, never()).listEntries(it) }
        verifyNoInteractions(characters, weapons, artifacts)
    }

    @Test
    fun `only changed category is downloaded and checkpointed`() {
        unchangedCatalogs()
        val menu = HoyolabWikiMenu.MATERIALS
        `when`(source.probeCatalog(menu)).thenReturn(HoyolabCatalogProbe(2, 164))
        val entries = listOf(HoyolabWikiEntry(164, "Teachings of Freedom"), HoyolabWikiEntry(163, "Guide to Freedom"))
        `when`(source.listEntries(menu)).thenReturn(entries)
        `when`(materials.synchronizeHoyolabMaterialList(entries.mapNotNull(HoyolabCatalogEntryMapper()::toMaterialSummary)))
            .thenReturn(2)
        assertEquals(2, service.synchronizeNow())
        assertEquals(listOf(menu), recordedMenus())
        menus.filter { it != menu }.forEach { verify(source, never()).listEntries(it) }
    }

    @Test
    fun `same-count first-entry changes and expired checkpoints cause refreshes`() {
        unchangedCatalogs()
        `when`(source.probeCatalog(HoyolabWikiMenu.WEAPONS)).thenReturn(HoyolabCatalogProbe(1, 2))
        `when`(store.checkpoint(HoyolabWikiMenu.ARTIFACTS))
            .thenReturn(HoyowikiCatalogCheckpoint(1, 1, Instant.now().minusSeconds(8 * 86400)))
        val weaponEntries = listOf(HoyolabWikiEntry(2, "New weapon", iconUrl = "https://bbs.hoyolab.com/icon.png"))
        val artifactEntries = listOf(HoyolabWikiEntry(1, "Updated set"))
        `when`(source.listEntries(HoyolabWikiMenu.WEAPONS)).thenReturn(weaponEntries)
        `when`(source.listEntries(HoyolabWikiMenu.ARTIFACTS)).thenReturn(artifactEntries)
        service.synchronizeNow()
        verify(source).listEntries(HoyolabWikiMenu.WEAPONS)
        verify(source).listEntries(HoyolabWikiMenu.ARTIFACTS)
        verify(source, never()).listEntries(HoyolabWikiMenu.MATERIALS)
        verify(source, never()).listEntries(HoyolabWikiMenu.CHARACTERS)
    }

    @Test
    fun `failed download keeps baseline and does not block another changed category`() {
        unchangedCatalogs()
        val materialMenu = HoyolabWikiMenu.MATERIALS
        val artifactMenu = HoyolabWikiMenu.ARTIFACTS
        `when`(source.probeCatalog(materialMenu)).thenReturn(HoyolabCatalogProbe(2, 2))
        `when`(source.listEntries(materialMenu)).thenThrow(IllegalStateException("Partial download"))
        `when`(source.probeCatalog(artifactMenu)).thenReturn(HoyolabCatalogProbe(2, 2))
        val entries = listOf(HoyolabWikiEntry(2, "New set"), HoyolabWikiEntry(1, "Old set"))
        `when`(source.listEntries(artifactMenu)).thenReturn(entries)
        service.synchronizeNow()
        assertEquals(listOf(artifactMenu), recordedMenus())
    }

    @Test
    fun `application ready returns while background probes are still waiting`() {
        unchangedCatalogs()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        `when`(source.probeCatalog(HoyolabWikiMenu.MATERIALS)).thenAnswer {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            HoyolabCatalogProbe(1, 1)
        }
        try {
            service.onApplicationReady()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(1L, release.count)
            service.onApplicationReady()
        } finally {
            release.countDown()
            service.close()
        }
    }
}
