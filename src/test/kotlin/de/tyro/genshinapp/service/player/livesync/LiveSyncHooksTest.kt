package de.tyro.genshinapp.service.player.livesync

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.*
import de.tyro.genshinapp.service.catalog.MaterialCatalogService
import de.tyro.genshinapp.service.player.SnapshotActivityService
import de.tyro.genshinapp.service.player.SnapshotActivityType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveSyncHooksTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `deltas include additions spending and removed keys and ignore metadata`() {
        val change = change(mapOf("old" to 3L, "spend" to 4L), mapOf("new" to 2L, "spend" to 1L))
        assertEquals(mapOf("old" to -3L, "spend" to -3L, "new" to 2L), change.materials.associate { it.key to it.delta })
        val received = mutableListOf<LiveSyncChange>()
        val dispatcher = LiveSyncHookDispatcher(listOf(LiveSyncHook { received += it }))
        dispatcher.dispatch(change)
        dispatcher.dispatch(change.copy(previous = change.current, current = change.current.copy(revision = 99)))
        assertEquals(listOf(change), received)
    }

    @Test
    fun `failing hook does not prevent later hooks or equipment only changes`() {
        val received = mutableListOf<LiveSyncChange>()
        val dispatcher = LiveSyncHookDispatcher(listOf(
            LiveSyncHook { error("Broken custom hook") },
            LiveSyncHook { received += it },
        ))
        val before = snapshot(emptyMap())
        val after = before.copy(weapons = listOf(PlayerWeapon("DullBlade", 1, 0, 1, null, false)))
        dispatcher.dispatch(LiveSyncChange(7, "session", before, after))
        assertEquals(1, received.size)
        assertTrue(received.single().materials.isEmpty())
    }

    @Test
    fun `groups multiple drops per boss persists results and ignores shared gems`() {
        val service = activityService()
        val hook = WeeklyBossCollectionHook(catalog(), service)
        hook.onChange(change(
            mapOf("dvalinsplume" to 4L),
            mapOf("dvalinsplume" to 5L, "dvalinsclaw" to 2L, "113006" to 1L, "vajradaamethystsliver" to 9L),
        ))
        val events = activityService().recent(7)
        assertEquals(listOf("Stormterror", "Wolf of the North"), events.map { it.name })
        assertEquals(listOf(3L, 1L), events.map { it.amount })
        assertTrue(events.all { it.type == SnapshotActivityType.WEEKLY_BOSS_COLLECTED && it.occurredAt == Instant.EPOCH })
        assertTrue(service.recent(8).isEmpty())
    }

    @Test
    fun `conversions spending unchanged drops unknown materials and gems do not report a boss`() {
        val service = activityService()
        val hook = WeeklyBossCollectionHook(catalog(), service)
        hook.onChange(change(
            mapOf("dvalinsplume" to 3L, "dvalinsclaw" to 1L, "tailofboreas" to 2L),
            mapOf("dvalinsplume" to 1L, "dvalinsclaw" to 3L, "tailofboreas" to 1L, "mora" to 100L),
        ))
        hook.onChange(change(mapOf("dvalinsplume" to 5L), mapOf("dvalinsplume" to 5L, "vajradaamethystsliver" to 2L)))
        hook.onChange(change(emptyMap(), mapOf("unknown" to 1L)))
        assertTrue(service.recent(7).isEmpty())
    }

    private fun catalog(): MaterialCatalogService = mock(MaterialCatalogService::class.java).also {
        `when`(it.getSources(listOf(MaterialSourceType.WEEKLY_BOSS))).thenReturn(listOf(
            MaterialSourceDefinition("stormterror", "Stormterror", MaterialSourceType.WEEKLY_BOSS, materials = listOf(
                MaterialSourceMaterialDefinition(MaterialDefinition(113003, "Dvalin's Plume"), MaterialSourceRole.DROP),
                MaterialSourceMaterialDefinition(MaterialDefinition(113004, "Dvalin's Claw"), MaterialSourceRole.DROP),
                MaterialSourceMaterialDefinition(MaterialDefinition(104141, "Vajrada Amethyst Sliver"), MaterialSourceRole.GEM),
            )),
            MaterialSourceDefinition("boreas", "Wolf of the North", MaterialSourceType.WEEKLY_BOSS, materials = listOf(
                MaterialSourceMaterialDefinition(MaterialDefinition(113006, "Tail of Boreas"), MaterialSourceRole.DROP),
            )),
        ))
    }

    private fun activityService() = SnapshotActivityService(
        GenshinContentProperties().also { it.cacheDirectory = directory.toString() },
        jacksonObjectMapper().findAndRegisterModules(),
    )

    private fun change(before: Map<String, Long>, after: Map<String, Long>) =
        LiveSyncChange(7, "session", snapshot(before), snapshot(after), Instant.EPOCH)
}

internal fun snapshot(inventory: Map<String, Long>) = PlayerSnapshot(
    formatVersion = 3, source = "Irminsul", importedAt = Instant.EPOCH,
    characters = emptyList(), inventory = inventory, inventoryNames = emptyMap(),
    exportedInventoryKeys = inventory.size, artifacts = emptyList(), weapons = emptyList(),
)
