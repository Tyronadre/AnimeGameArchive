package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
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
            "A Teaspoon of Transcendence",
            catalog.officialName("ateaspoonoftranscendence"),
        )
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

    @Test
    fun `refreshes generated defaults on restart and preserves overrides`() {
        val mapper = jacksonObjectMapper()
        val cacheDirectory = Files.createTempDirectory("weapon-default-refresh")
        val oldProperties = GenshinContentProperties().also {
            it.cacheDirectory = cacheDirectory.toString()
            it.fandomImageBaseUrl = "https://old-default.example/images"
        }
        val store = InMemoryWeaponCatalogStore(
            WeaponDefinition(
                key = "rust",
                name = "Rust",
                remoteImageUrl = FandomImageUrlResolver(oldProperties).weaponImageUrl("Rust"),
            ),
        )
        val oldRegistry = ImageUrlRegistry(mapper, oldProperties)
        WeaponCatalogService(
            mapper,
            store,
            FandomImageUrlResolver(oldProperties),
            oldRegistry,
        )
        val overrideUrl = "https://custom.example/rust.png"
        oldRegistry.setWeaponOverride("rust", "Rust", overrideUrl)

        val newProperties = GenshinContentProperties().also {
            it.cacheDirectory = cacheDirectory.toString()
            it.fandomImageBaseUrl = "https://new-default.example/images"
        }
        val newResolver = FandomImageUrlResolver(newProperties)
        val newRegistry = ImageUrlRegistry(mapper, newProperties)
        val restartedCatalog = WeaponCatalogService(
            mapper,
            store,
            newResolver,
            newRegistry,
        )

        val expectedDefault = newResolver.weaponImageUrl("Rust")
        assertEquals(expectedDefault, assertNotNull(restartedCatalog.find("rust")).remoteImageUrl)
        val refreshedLink = assertNotNull(newRegistry.weaponLink("rust"))
        assertEquals(expectedDefault, refreshedLink.defaultUrl)
        assertEquals(overrideUrl, refreshedLink.effectiveUrl)
        assertTrue(refreshedLink.hasOverride)
    }

    private class InMemoryWeaponCatalogStore(initial: WeaponDefinition) : WeaponCatalogStore {
        private val weapons = ConcurrentHashMap<String, WeaponDefinition>().also {
            it[initial.key] = initial
        }

        override fun getWeapons(): List<WeaponDefinition> = weapons.values.toList()

        override fun findWeapon(key: String): WeaponDefinition? = weapons[key.lowercase()]

        override fun saveWeapon(weapon: WeaponDefinition): WeaponDefinition = weapon.also {
            weapons[weapon.key] = weapon
        }
    }
}
