package de.tyro.genshinapp.service.importer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.service.catalog.*
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import org.springframework.boot.DefaultApplicationArguments
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class BundledCatalogStartupTest {
    @Test
    fun `the same packaged snapshot is only projected once`(@TempDir directory: Path) {
        val characters = mock(CharacterCatalogService::class.java)
        val materials = mock(MaterialCatalogService::class.java)
        val weapons = mock(WeaponCatalogService::class.java)
        val artifacts = mock(ArtifactCatalogService::class.java)
        val store = mock(HoyowikiCatalogStore::class.java)
        val version = "2026-10-01T00:00:00Z"
        val imported = AtomicBoolean()
        `when`(store.hasBundledVersion(version)).thenAnswer { imported.get() }
        doAnswer { imported.set(true); null }.`when`(store).markBundledVersion(version)
        `when`(characters.getCharacters()).thenReturn(emptyList())
        val snapshot = BundledCatalogSnapshot(
            generatedAt = version,
            hoyolabMaterials = listOf(HoyolabWikiEntry(164, "Teachings of Freedom")),
            hoyolabCharacters = listOf(HoyolabWikiEntry(10, "Character")),
            hoyolabWeapons = listOf(HoyolabWikiEntry(20, "Weapon", iconUrl = "https://bbs.hoyolab.com/weapon.png")),
            hoyolabArtifacts = listOf(HoyolabWikiEntry(30, "Artifact")),
            hoyolabCharacterPages = listOf(jacksonObjectMapper().readTree("""{"id":"10","name":"Character"}""")),
            hoyolabWeaponPages = listOf(jacksonObjectMapper().readTree("""{"id":"20","name":"Weapon"}""")),
        )
        val resource = directory.resolve("data/bundled-catalog-snapshot.json")
        Files.createDirectories(resource.parent)
        Files.writeString(resource, jacksonObjectMapper().writeValueAsString(snapshot))
        val previousLoader = Thread.currentThread().contextClassLoader
        val loader = object : URLClassLoader(arrayOf(directory.toUri().toURL()), previousLoader) {
            override fun getResource(name: String): URL? =
                if (name == "data/bundled-catalog-snapshot.json") resource.toUri().toURL()
                else super.getResource(name)
        }
        try {
            Thread.currentThread().contextClassLoader = loader
            val importer = BundledCatalogSnapshotImporter(characters, materials, weapons, artifacts,
                HoyolabCatalogEntryMapper(), mock(HoyolabCharacterPageService::class.java),
                mock(HoyolabWeaponGalleryService::class.java), GenshinContentProperties(), store)
            importer.run(DefaultApplicationArguments())
            importer.run(DefaultApplicationArguments())
            val projections = mockingDetails(store).invocations.filter { it.method.name == "recordCatalog" }
            assertEquals(4, projections.size)
            assertTrue(projections.all { it.arguments[3] == false })
            verify(artifacts).synchronizeHoyolabArtifactList(snapshot.hoyolabArtifacts)
            verify(artifacts).refreshFromDatabase()
        } finally {
            Thread.currentThread().contextClassLoader = previousLoader
            loader.close()
        }
    }
}
