package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WeaponCatalogServiceTest {
    private val catalog = WeaponCatalogService(jacksonObjectMapper())

    @Test
    fun `resolves GOOD keys to official weapon names and icon routes`() {
        assertEquals("Wolf's Gravestone", catalog.officialName("WolfsGravestone"))
        assertEquals(
            "/media/weapons/WolfsGravestone",
            catalog.imageUrl("WolfsGravestone"),
        )
    }

    @Test
    fun `covers every weapon in the supplied GOOD export`() {
        val snapshot = GoodImportService(jacksonObjectMapper())
            .parse(Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT))

        snapshot.weapons.forEach { weapon ->
            assertNotNull(
                catalog.officialName(weapon.key),
                "Missing official name for ${weapon.key}",
            )
        }
        assertTrue(catalog.imageUrls(snapshot.weapons).isNotEmpty())
    }
}
