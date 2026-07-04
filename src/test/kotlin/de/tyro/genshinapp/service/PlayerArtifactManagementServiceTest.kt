package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.GoodKeyNormalizer
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerArtifactManagementServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val objectMapper = jacksonObjectMapper()
    private val importService = GoodImportService(objectMapper)
    private val catalog = ArtifactCatalogService(objectMapper)

    @Test
    fun `assigns and swaps artifacts by slot`() {
        val fixture = fixture()
        val before = fixture.store.current(USER_ID)!!
        val tartagliaFlower = before.artifacts.indexOfFirst {
            it.slotKey == "flower" &&
                GoodKeyNormalizer.normalize(it.location.orEmpty()) == "tartaglia"
        }
        val furinaFlower = before.artifacts.indexOfFirst {
            it.slotKey == "flower" &&
                GoodKeyNormalizer.normalize(it.location.orEmpty()) == "furina"
        }
        assertTrue(tartagliaFlower >= 0)
        assertTrue(furinaFlower >= 0)

        val result = fixture.service.assign(USER_ID, furinaFlower, "Tartaglia")
        val after = fixture.store.current(USER_ID)!!

        assertTrue(result.swapped)
        assertEquals("Tartaglia", after.artifacts[furinaFlower].location)
        assertEquals("Furina", after.artifacts[tartagliaFlower].location)

        fixture.service.assign(USER_ID, furinaFlower, null)
        assertNull(fixture.store.current(USER_ID)!!.artifacts[furinaFlower].location)
    }

    @Test
    fun `creates levels edits and reloads artifact overrides`() {
        val fixture = fixture()
        val originalCount = fixture.store.current(USER_ID)!!.artifacts.size
        val request = ArtifactMutationRequest(
            setKey = "HeartOfDepth",
            slotKey = "circlet",
            level = 16,
            rarity = 5,
            mainStatKey = "critRate_",
            locked = true,
            astralMark = false,
            elixirCrafted = false,
            substats = listOf(
                ArtifactStatInput("critDMG_", 21.8),
                ArtifactStatInput("enerRech_", 11.0),
            ),
            totalRolls = 5,
        )

        fixture.service.create(USER_ID, request)
        val created = fixture.store.current(USER_ID)!!.artifacts.last()
        assertEquals(originalCount + 1, fixture.store.current(USER_ID)!!.artifacts.size)
        assertEquals(16, created.level)
        assertEquals("critRate_", created.mainStatKey)
        assertEquals(5, created.totalRolls)

        fixture.service.update(
            USER_ID,
            originalCount,
            request.copy(level = 20, locked = false),
        )
        val reloadedStore = PlayerSnapshotStore(
            fixture.properties,
            importService,
            objectMapper,
        )
        val reloaded = reloadedStore.current(USER_ID)!!.artifacts.last()

        assertEquals(20, reloaded.level)
        assertEquals(false, reloaded.locked)
        assertEquals(21.8, reloaded.substats.first().value)
        assertEquals(5, reloaded.totalRolls)

        assertFailsWith<IllegalArgumentException> {
            fixture.service.create(
                USER_ID,
                request.copy(level = 0, totalRolls = 9),
            )
        }

        reloadedStore.save(
            USER_ID,
            Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT),
        )
        assertEquals(originalCount, reloadedStore.current(USER_ID)!!.artifacts.size)
    }

    private fun fixture(): Fixture {
        val properties = GenshinContentProperties().also {
            it.cacheDirectory = temporaryDirectory.toString()
        }
        val store = PlayerSnapshotStore(properties, importService, objectMapper)
        store.save(USER_ID, Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT))
        return Fixture(
            properties = properties,
            store = store,
            service = PlayerArtifactManagementService(
                store,
                catalog,
                ArtifactOptimizationService(catalog),
            ),
        )
    }

    private data class Fixture(
        val properties: GenshinContentProperties,
        val store: PlayerSnapshotStore,
        val service: PlayerArtifactManagementService,
    )

    companion object {
        private const val USER_ID = 77L
    }
}
