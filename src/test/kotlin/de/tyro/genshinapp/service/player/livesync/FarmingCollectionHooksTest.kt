package de.tyro.genshinapp.service.player.livesync

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialDefinition
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.service.catalog.MaterialCatalogService
import de.tyro.genshinapp.service.player.SnapshotActivityEvent
import de.tyro.genshinapp.service.player.SnapshotActivityService
import de.tyro.genshinapp.service.player.SnapshotActivityType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class FarmingCollectionHooksTest {
    @TempDir lateinit var directory: Path
    private val catalog = mock(MaterialCatalogService::class.java).also {
        `when`(it.getMaterials()).thenReturn(listOf(
            MaterialDefinition(104301, "Teachings of Freedom", MaterialCategory.TALENT_BOOK, "talent:freedom"),
            MaterialDefinition(104302, "Guide to Freedom", MaterialCategory.TALENT_BOOK, "talent:freedom"),
            MaterialDefinition(104304, "Teachings of Resistance", MaterialCategory.TALENT_BOOK, "talent:resistance"),
            MaterialDefinition(114001, "Tile of Decarabian's Tower", MaterialCategory.WEAPON_ASCENSION),
            MaterialDefinition(114002, "Debris of Decarabian's City", MaterialCategory.WEAPON_ASCENSION),
            MaterialDefinition(113068, "Eroded Horn", MaterialCategory.WEEKLY_BOSS),
            MaterialDefinition(113001, "Hurricane Seed", MaterialCategory.WORLD_BOSS),
            MaterialDefinition(104111, "Agnidus Agate Sliver", MaterialCategory.GEM),
        ))
    }
    private val detector = FarmingCollectionDetector(catalog)

    @Test
    fun `Mora bundles are inferred without inventing a resin cost`() {
        val event = assertNotNull(detect(after = mapOf("mora" to 60_000)))
        assertEquals(SnapshotActivityType.MORA_LEY_LINE, event.type)
        assertEquals("ley-line:mora", event.taskKey)
        assertNull(event.resinCost)
        assertNull(detect(after = mapOf("mora" to 347)))
        assertNull(detect(after = mapOf("mora" to 60_000, "primogem" to 20)))
    }

    @Test
    fun `EXP bundles support single double and triple claims without treating gifted books as a claim`() {
        for (multiplier in 1L..3L) {
            val event = assertNotNull(detect(after = mapOf("heroswit" to 4 * multiplier, "adventurersexperience" to 6 * multiplier)))
            assertEquals(SnapshotActivityType.EXPERIENCE_LEY_LINE, event.type)
        }
        assertNull(detect(after = mapOf("heroswit" to 1)))
        assertNull(detect(after = mapOf("heroswit" to 4, "adventurersexperience" to 6, "mora" to 60000)))
    }

    @Test
    fun `known original resin spend and condensed consumption determine cost`() {
        for (resin in listOf(20, 40, 60)) {
            val event = assertNotNull(detect(mapOf("originalresin" to 180), mapOf("originalresin" to 180L - resin, "mora" to 60000)))
            assertEquals(resin, event.resinCost)
        }
        val condensed = assertNotNull(detect(mapOf("condensedresin" to 1), mapOf("mora" to 180000)))
        assertEquals(60, condensed.resinCost)
        assertNull(detect(mapOf("originalresin" to 180), mapOf("originalresin" to 120, "condensedresin" to 1)))
        assertNull(detect(mapOf("condensedresin" to 2), mapOf("mora" to 360000)))
    }

    @Test
    fun `talent and weapon domain tiers produce one event per reward bundle`() {
        val talent = assertNotNull(detect(after = mapOf("teachingsoffreedom" to 2, "guidetofreedom" to 2, "mora" to 2000)))
        assertEquals(SnapshotActivityType.TALENT_DOMAIN, talent.type)
        assertEquals("talent:talent:freedom", talent.taskKey)
        val weapon = assertNotNull(detect(after = mapOf("114001" to 2, "114002" to 2, "mora" to 2000)))
        assertEquals(SnapshotActivityType.WEAPON_DOMAIN, weapon.type)
        assertEquals("weapon:114001", weapon.taskKey)
        assertNull(detect(after = mapOf("teachingsoffreedom" to 1)))
    }

    @Test
    fun `crafting mixed families and boss rewards are not ley lines or domains`() {
        assertNull(detect(mapOf("teachingsoffreedom" to 3), mapOf("guidetofreedom" to 1)))
        assertNull(detect(after = mapOf("teachingsoffreedom" to 2, "teachingsofresistance" to 2, "mora" to 2000)))
        for (key in listOf("erodedhorn", "hurricaneseed", "agnidusagatesliver")) {
            assertNull(detect(after = mapOf(key to 1, "mora" to 60000)))
        }
    }

    @Test
    fun `new artifact bundles are counted once while strongboxing upgrades and boss sets are ignored`() {
        val old = artifact("GoldenTroupe")
        val gained = listOf(artifact("DeepwoodMemories"), artifact("GildedDreams"))
        fun detectArtifacts(before: List<PlayerArtifact>, after: List<PlayerArtifact>) = detector.detect(change(
            snapshot(emptyMap()).copy(artifacts = before),
            snapshot(mapOf("mora" to 2525L)).copy(artifacts = after),
        ))
        assertEquals(SnapshotActivityType.ARTIFACT_DOMAIN, detectArtifacts(listOf(old), listOf(old) + gained)?.type)
        assertNull(detectArtifacts(listOf(old), gained))
        assertNull(detectArtifacts(listOf(old), listOf(old.copy(level = 4))))
        assertNull(detectArtifacts(emptyList(), listOf(artifact("GladiatorsFinale"), artifact("WanderersTroupe"))))
        assertNull(detectArtifacts(emptyList(), listOf(old.copy(elixirCrafted = true)) + gained))
        // Identical copies are real additions; equipment lock/location changes are not.
        assertNotNull(detectArtifacts(listOf(old), listOf(old.copy(locked = true), old, old)))
        assertNull(detectArtifacts(listOf(old), listOf(old.copy(location = "Furina", locked = true))))
    }

    @Test
    fun `repeatable claims persist independently of the feed and retries do not double count`() {
        val service = activity()
        val dispatcher = LiveSyncHookDispatcher(listOf(LeyLineCollectionHook(detector, service), DomainCollectionHook(detector, service)))
        val now = Instant.parse("2026-10-24T12:00:00Z")
        val first = change(snapshot(mapOf("condensedresin" to 2)), snapshot(mapOf("condensedresin" to 1, "mora" to 180000)), now)
        dispatcher.dispatch(first)
        dispatcher.dispatch(first)
        val second = change(first.current, snapshot(mapOf("mora" to 360000)).copy(revision = 2), now.plusSeconds(60))
        dispatcher.dispatch(second)
        service.recordEvents(7, (1..60).map { SnapshotActivityEvent(name = "Mora") })
        val progress = activity().dailyFarmingProgress(7, now.plusSeconds(120))
        assertEquals(2, progress.runs.single().count)
        assertEquals(120, progress.runs.single().resin)
        assertEquals(0, progress.runs.single().unconfirmedCount)
        assertTrue(activity().dailyFarmingProgress(8, now).runs.isEmpty())
        assertTrue(activity().dailyFarmingProgress(7, Instant.parse("2026-10-25T03:00:00Z")).runs.isEmpty())
    }

    @Test
    fun `unconfirmed costs stay unconfirmed and domain hook writes only one event`() {
        val service = activity()
        val dispatcher = LiveSyncHookDispatcher(listOf(LeyLineCollectionHook(detector, service), DomainCollectionHook(detector, service)))
        val event = change(snapshot(emptyMap()), snapshot(mapOf("teachingsoffreedom" to 2, "guidetofreedom" to 2, "mora" to 2000)))
        dispatcher.dispatch(event)
        assertEquals(1, service.recent(7).size)
        val run = service.dailyFarmingProgress(7, event.occurredAt).runs.single()
        assertEquals(1, run.count)
        assertEquals(0, run.resin)
        assertEquals(1, run.unconfirmedCount)
    }

    private fun activity() = SnapshotActivityService(
        GenshinContentProperties().also { it.cacheDirectory = directory.toString() },
        jacksonObjectMapper().findAndRegisterModules(),
    )
    private fun detect(before: Map<String, Long> = emptyMap(), after: Map<String, Long>) = detector.detect(change(snapshot(before), snapshot(after)))
    private fun change(before: PlayerSnapshot, after: PlayerSnapshot, time: Instant = Instant.EPOCH) = LiveSyncChange(7, "session", before, after, time)
    private fun artifact(set: String) = PlayerArtifact(set, "flower", 0, 5, "hp", null, false, emptyList(), 0, false, false)
}
