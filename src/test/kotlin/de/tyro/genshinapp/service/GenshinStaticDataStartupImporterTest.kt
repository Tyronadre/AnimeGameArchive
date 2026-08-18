package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.boot.DefaultApplicationArguments
import kotlin.test.Test

class GenshinStaticDataStartupImporterTest {
    @Test
    fun `runs the configured import only once and refreshes projections`() {
        val properties = GenshinContentProperties().also {
            it.staticImportFolders = listOf("artifacts")
        }
        val source = mock(GenshinStaticDataSource::class.java)
        val catalog = mock(GenshinStaticDataCatalog::class.java)
        val characterCatalog = mock(CharacterCatalogService::class.java)
        val materialCatalog = mock(MaterialCatalogService::class.java)
        val weaponData = mock(WeaponDataService::class.java)
        val artifactCatalog = mock(ArtifactCatalogService::class.java)
        val artifact = jacksonObjectMapper().readTree("""{"name":"Test Set"}""")
        `when`(source.fetchFolder("artifacts")).thenReturn(listOf(artifact))
        `when`(catalog.synchronize("artifacts", listOf(artifact))).thenReturn(
            StaticDataSyncResult("artifacts", 1, 1, 0, 0, 0),
        )
        `when`(catalog.readFolder("characters")).thenReturn(emptyList())
        `when`(catalog.readFolder("weapons")).thenReturn(emptyList())
        val importer = GenshinStaticDataStartupImporter(
            properties,
            source,
            catalog,
            characterCatalog,
            materialCatalog,
            weaponData,
            artifactCatalog,
        )
        val arguments = DefaultApplicationArguments()

        importer.run(arguments)
        importer.run(arguments)

        verify(source, times(1)).fetchFolder("artifacts")
        verify(catalog, times(1)).synchronize("artifacts", listOf(artifact))
        verify(artifactCatalog, times(1)).refreshFromDatabase()
    }
}
