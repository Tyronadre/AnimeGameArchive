package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.model.PlayerArtifact
import java.nio.file.Files
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
}
