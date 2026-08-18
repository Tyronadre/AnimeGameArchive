package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.entity.GenshinStaticData
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.repository.GenshinStaticDataRepository
import java.nio.file.Files
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArtifactCatalogServiceTest {
    private val catalog = ArtifactCatalogService(jacksonObjectMapper())

    @Test
    fun `resolves a GOOD set and slot to its artifact icon route`() {
        assertEquals("Gilded Corsage", catalog.pieceName("HeartOfDepth", "flower"))
        assertEquals(
            "/media/artifacts/HeartOfDepth/flower",
            catalog.imageUrl("HeartOfDepth", "flower"),
        )
    }

    @Test
    fun `covers every artifact in the supplied GOOD export`() {
        val snapshot = GoodImportService(jacksonObjectMapper())
            .parse(Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT))

        snapshot.artifacts.forEach { artifact ->
            assertNotNull(
                catalog.imageUrl(artifact.setKey, artifact.slotKey),
                "Missing icon for ${artifact.setKey}/${artifact.slotKey}",
            )
        }
        assertTrue(catalog.imageUrls(snapshot.artifacts).isNotEmpty())
    }

    @Test
    fun `overlays imported artifact sets on the bundled fallback`() {
        val repository = mock(GenshinStaticDataRepository::class.java)
        `when`(repository.findAllByFolderOrderByNameAsc("artifacts")).thenReturn(
            listOf(
                GenshinStaticData().also {
                    it.folder = "artifacts"
                    it.catalogKey = "remoteset"
                    it.name = "Remote Set"
                    it.sourceJson = """
                        {
                          "name":"Remote Set",
                          "flower":{"name":"Remote Flower"},
                          "circlet":{"name":"Remote Crown"}
                        }
                    """.trimIndent()
                },
            ),
        )

        val importedCatalog = ArtifactCatalogService(jacksonObjectMapper(), repository)

        assertEquals("Remote Set", importedCatalog.setName("RemoteSet"))
        assertEquals("Remote Flower", importedCatalog.pieceName("RemoteSet", "flower"))
        assertEquals("Gilded Corsage", importedCatalog.pieceName("HeartOfDepth", "flower"))
    }
}
