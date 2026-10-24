package de.tyro.genshinapp.service.player.livesync

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.DesktopUserProvider
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.desktop.irminsul.*
import de.tyro.genshinapp.security.AppUserPrincipal
import de.tyro.genshinapp.service.player.GoodImportService
import de.tyro.genshinapp.service.player.PlayerSnapshotStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import org.springframework.context.ApplicationContext
import org.springframework.test.util.ReflectionTestUtils
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IrminsulLiveSyncHooksTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `only changes after a successful session baseline invoke hooks`() {
        val (service, store, received) = fixture()
        service.receiveSnapshot(payload(1)) // Import outside capture is not a live event.
        ReflectionTestUtils.setField(service, "activeSession", "first")
        service.receiveSnapshot(payload(8)) // Do not infer activity since the previous import.
        assertTrue(received.isEmpty())
        service.receiveSnapshot(payload(10))
        service.receiveSnapshot(payload(10)) // Repeated upload.
        assertEquals(listOf(2L), received.map { it.materials.single().delta })
        assertEquals(10L, store.current(7)?.inventory?.get("dvalinsplume"))

        store.updateInventoryAmount(7, "dvalinsplume", 0)
        service.receiveSnapshot(payload(11)) // Compare captures, not manually edited storage.
        assertEquals(1L, received.last().materials.single().delta)

        service.receiveStatus(IrminsulStatusEvent("stopped"))
        ReflectionTestUtils.setField(service, "activeSession", "second")
        service.receiveSnapshot(payload(20))
        assertEquals(2, received.size)
        service.receiveSnapshot(payload(21))
        assertEquals("second", received.last().sessionId)
        assertEquals(1L, received.last().materials.single().delta)
    }

    @Test
    fun `failed imports do not invoke hooks and a new session needs a fresh baseline`() {
        val (service, _, received) = fixture()
        ReflectionTestUtils.setField(service, "activeSession", "first")
        service.receiveSnapshot(payload(1))
        assertEquals(IrminsulCaptureState.ERROR, service.receiveSnapshot("invalid".toByteArray()).state)
        assertTrue(received.isEmpty())
        ReflectionTestUtils.setField(service, "activeSession", "second")
        service.receiveSnapshot(payload(10))
        assertTrue(received.isEmpty())
        service.receiveSnapshot(payload(11))
        assertEquals(1L, received.single().materials.single().delta)
    }

    @Test
    fun `hook failure leaves capture live and persisted state advances`() {
        val (service, store, received) = fixture(failingHook = true)
        ReflectionTestUtils.setField(service, "activeSession", "first")
        service.receiveSnapshot(payload(1))
        assertEquals(IrminsulCaptureState.LIVE, service.receiveSnapshot(payload(3)).state)
        service.receiveSnapshot(payload(3))
        service.receiveSnapshot(payload(4))
        assertEquals(listOf(2L, 1L), received.map { it.materials.single().delta })
        assertEquals(4L, store.current(7)?.inventory?.get("dvalinsplume"))
    }

    private fun fixture(failingHook: Boolean = false): Triple<IrminsulIntegrationService, PlayerSnapshotStore, MutableList<LiveSyncChange>> {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val store = PlayerSnapshotStore(
            GenshinContentProperties().also { it.cacheDirectory = directory.toString() },
            GoodImportService(mapper), mapper,
        )
        val provider = mock(DesktopUserProvider::class.java)
        `when`(provider.principal()).thenReturn(AppUserPrincipal(7, "Test", "test@example.com", "unused"))
        val received = mutableListOf<LiveSyncChange>()
        val hooks = buildList {
            if (failingHook) add(LiveSyncHook { error("Custom hook failure") })
            add(LiveSyncHook { change ->
                assertEquals(change.current.inventory, store.current(change.userId)?.inventory)
                received += change
            })
        }
        return Triple(IrminsulIntegrationService(
            mock(ApplicationContext::class.java), IrminsulProperties(), provider, store,
            LiveSyncHookDispatcher(hooks),
        ), store, received)
    }

    private fun payload(amount: Long) =
        """{"format":"GOOD","version":3,"characters":[],"materials":{"DvalinsPlume":$amount}}""".toByteArray()
}
