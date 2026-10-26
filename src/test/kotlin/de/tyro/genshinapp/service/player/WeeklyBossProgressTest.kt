package de.tyro.genshinapp.service.player

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeeklyBossProgressTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `three materials count as one claim and survive feed eviction and restart`() {
        val service = service()
        service.recordEvents(7, listOf(claim("Lord of Eroded Primal Fire", "2026-10-24T12:00:00Z")))
        service.recordEvents(7, listOf(claim("Lord of Eroded Primal Fire", "2026-10-24T12:00:03Z")))
        service.recordEvents(7, (1..60).map { SnapshotActivityEvent(name = "Mora", amount = 1) })
        assertTrue(service.recent(7, 50).none { it.type == SnapshotActivityType.WEEKLY_BOSS_COLLECTED })

        val progress = service().weeklyBossProgress(7, Instant.parse("2026-10-24T13:00:00Z"))
        assertEquals("2026-10-19", progress.week)
        assertEquals("2026-10-24", progress.day)
        assertEquals(listOf(WeeklyBossClaim("lordoferodedprimalfire", "2026-10-24")), progress.claims)
        assertTrue(service().weeklyBossProgress(8, Instant.parse("2026-10-24T13:00:00Z")).claims.isEmpty())
    }

    @Test
    fun `game reset starts a new week and allows the same boss again`() {
        val service = service()
        service.recordEvents(7, listOf(claim("Stormterror", "2026-10-26T02:59:59Z")))
        assertEquals("2026-10-25", service.weeklyBossProgress(7, Instant.parse("2026-10-26T02:59:59Z")).day)
        assertTrue(service.weeklyBossProgress(7, Instant.parse("2026-10-26T03:00:00Z")).claims.isEmpty())
        service.recordEvents(7, listOf(claim("Stormterror", "2026-10-26T03:00:00Z")))
        val progress = service.weeklyBossProgress(7, Instant.parse("2026-10-26T03:00:00Z"))
        assertEquals("2026-10-26", progress.week)
        assertEquals(listOf(WeeklyBossClaim("stormterror", "2026-10-26")), progress.claims)
    }

    @Test
    fun `existing detected activity is backfilled without requiring another collection`() {
        val history = directory.resolve("player-data/7/recent-activity.json")
        Files.createDirectories(history.parent)
        Files.write(history, mapper.writeValueAsBytes(listOf(
            claim("Lord of Eroded Primal Fire", "2026-10-24T12:00:00Z"),
            claim("Stormterror", "2026-10-23T12:00:00Z"),
            claim("Childe", "2026-10-18T12:00:00Z"),
        )))
        val progress = service().weeklyBossProgress(7, Instant.parse("2026-10-24T13:00:00Z"))
        assertEquals(listOf("stormterror", "lordoferodedprimalfire"), progress.claims.map { it.bossKey })
        assertTrue(Files.isRegularFile(history.resolveSibling("weekly-boss-claims.json")))
        assertEquals(progress, service().weeklyBossProgress(7, Instant.parse("2026-10-24T13:00:00Z")))
    }

    private fun service() = SnapshotActivityService(
        GenshinContentProperties().also { it.cacheDirectory = directory.toString() }, mapper,
    )

    private fun claim(name: String, time: String) = SnapshotActivityEvent(
        type = SnapshotActivityType.WEEKLY_BOSS_COLLECTED,
        name = name, amount = 3, occurredAt = Instant.parse(time),
    )
}
