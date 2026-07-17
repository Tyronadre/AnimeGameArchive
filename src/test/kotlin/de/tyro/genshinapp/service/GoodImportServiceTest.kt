package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoodImportServiceTest {
    private val objectMapper = jacksonObjectMapper()
    private val service = GoodImportService(objectMapper)

    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `parses the supplied GOOD version 3 export`() {
        val snapshot = service.parse(Files.readAllBytes(SAMPLE_EXPORT))

        assertEquals(3, snapshot.formatVersion)
        assertEquals("Irminsul", snapshot.source)
        assertEquals(81, snapshot.characters.size)
        assertEquals(78L, snapshot.inventory["heroswit"])
        assertEquals(117L, snapshot.inventory["lakelightlily"])
        assertEquals(41L, snapshot.inventory["crownofinsight"])
        assertTrue(snapshot.exportedInventoryKeys > 1_000)
        assertEquals(1_177, snapshot.artifacts.size)
        assertEquals(280, snapshot.weapons.size)
        assertEquals("MaidenBeloved", snapshot.artifacts.first().setKey)
        assertEquals("RecurveBow", snapshot.weapons.first().key)
    }

    @Test
    fun `rejects json that is not in GOOD format`() {
        val exception = assertFailsWith<GoodImportException> {
            service.parse("""{"format":"something-else","version":3}""".toByteArray())
        }

        assertEquals("good.error.invalidFormat", exception.messageKey)
    }

    @Test
    fun `accepts the canonical GOOD traveler key`() {
        val snapshot = service.parse(
            """
            {
              "format": "GOOD",
              "version": 3,
              "source": "test",
              "characters": [{
                "key": "Traveler",
                "level": 80,
                "constellation": 4,
                "ascension": 5,
                "talent": {"auto": 6, "skill": 8, "burst": 8}
              }],
              "materials": {},
              "artifacts": [],
              "weapons": []
            }
            """.trimIndent().toByteArray(),
        )

        assertEquals("Traveler", snapshot.characters.single().key)
    }

    @Test
    fun `stores and reloads the validated GOOD export`() {
        val properties = GenshinContentProperties().also {
            it.cacheDirectory = temporaryDirectory.toString()
        }
        val bytes = Files.readAllBytes(SAMPLE_EXPORT)
        val store = PlayerSnapshotStore(properties, service, objectMapper)

        val saved = store.save(USER_ONE_ID, bytes)
        val updated = store.updateInventoryAmount(USER_ONE_ID, "heroswit", 999)
        val reloaded = PlayerSnapshotStore(properties, service, objectMapper).current(USER_ONE_ID)

        assertEquals(81, saved.characters.size)
        assertEquals(999L, updated.inventory["heroswit"])
        assertEquals(81, reloaded?.characters?.size)
        assertEquals(999L, reloaded?.inventory?.get("heroswit"))
        assertTrue(
            Files.isRegularFile(
                temporaryDirectory.resolve("player-data/$USER_ONE_ID/current-good.json"),
            ),
        )
        assertTrue(
            Files.isRegularFile(
                temporaryDirectory.resolve("player-data/$USER_ONE_ID/inventory-overrides.json"),
            ),
        )
    }

    @Test
    fun `records inventory override activity for live dashboard updates`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val properties = GenshinContentProperties().also {
            it.cacheDirectory = temporaryDirectory.toString()
        }
        val activityService = SnapshotActivityService(properties, mapper)
        val store = PlayerSnapshotStore(properties, GoodImportService(mapper), mapper, activityService)

        store.save(USER_ONE_ID, Files.readAllBytes(SAMPLE_EXPORT))
        val updated = store.updateInventoryAmount(USER_ONE_ID, "heroswit", 999)
        val activity = activityService.recent(USER_ONE_ID).first()

        assertEquals(999L, updated.inventory["heroswit"])
        assertEquals(SnapshotActivityType.MATERIAL_GAIN, activity.type)
        assertEquals("heroswit", activity.materialKey)
        assertEquals(921L, activity.amount)
        assertEquals(999L, activity.total)
    }

    @Test
    fun `keeps imported inventories isolated by user id`() {
        val properties = GenshinContentProperties().also {
            it.cacheDirectory = temporaryDirectory.toString()
        }
        val bytes = Files.readAllBytes(SAMPLE_EXPORT)
        val store = PlayerSnapshotStore(properties, service, objectMapper)

        store.save(USER_ONE_ID, bytes)
        store.save(USER_TWO_ID, bytes)
        store.updateInventoryAmount(USER_ONE_ID, "heroswit", 999)

        assertEquals(999L, store.current(USER_ONE_ID)?.inventory?.get("heroswit"))
        assertEquals(78L, store.current(USER_TWO_ID)?.inventory?.get("heroswit"))
        assertTrue(store.filePath(USER_ONE_ID) != store.filePath(USER_TWO_ID))
    }

    companion object {
        val SAMPLE_EXPORT: Path = Path.of("src", "test", "genshin_export_1.json")
        const val USER_ONE_ID = 11L
        const val USER_TWO_ID = 22L
    }
}
