package de.tyro.genshinapp

import de.tyro.genshinapp.configuration.DesktopUserProvider
import de.tyro.genshinapp.desktop.irminsul.IrminsulCaptureState
import de.tyro.genshinapp.desktop.irminsul.IrminsulIntegrationService
import de.tyro.genshinapp.desktop.irminsul.IrminsulStatusEvent
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.WeaponImageType
import de.tyro.genshinapp.repository.UserRepository
import de.tyro.genshinapp.repository.GameWeaponRepository
import de.tyro.genshinapp.repository.PlayerWeaponInstanceRepository
import de.tyro.genshinapp.service.GoodImportServiceTest
import de.tyro.genshinapp.service.ImageUrlRegistry
import de.tyro.genshinapp.service.PlayerSnapshotStore
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:desktop;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "genshin.content.cache-directory=build/test-desktop-cache",
        "genshin.content.hoyolab-wiki-enabled=false",
        "genshin.content.static-import-enabled=false",
    ],
)
@ActiveProfiles("desktop")
@AutoConfigureMockMvc
class DesktopModeIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val userRepository: UserRepository,
    private val integrationService: IrminsulIntegrationService,
    private val snapshotStore: PlayerSnapshotStore,
    private val gameWeaponRepository: GameWeaponRepository,
    private val playerWeaponRepository: PlayerWeaponInstanceRepository,
    private val imageUrlRegistry: ImageUrlRegistry,
) {
    @Test
    fun `desktop mode creates a local user and opens without a login`() {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Local Traveler")))
            .andExpect(content().string(not(containsString("Log out"))))

        assertNotNull(
            userRepository.findByEmailIgnoreCase(DesktopUserProvider.DESKTOP_EMAIL),
        )
    }

    @Test
    fun `desktop login page redirects to the application`() {
        mockMvc.perform(get("/login"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/"))
    }

    @Test
    fun `weapon detail page renders bundled weapon data`() {
        mockMvc.perform(get("/weapons/rust"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Rust")))
            .andExpect(content().string(containsString("Ascension materials")))

        assertEquals(4, gameWeaponRepository.findByKey("rust")?.rarity)
    }

    @Test
    fun `weapon catalog and owned copies are persisted`() {
        val user = assertNotNull(
            userRepository.findByEmailIgnoreCase(DesktopUserProvider.DESKTOP_EMAIL),
        )
        val snapshot = snapshotStore.save(
            requireNotNull(user.id),
            Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT),
        )
        val storedCopies = playerWeaponRepository
            .findAllByUser_IdOrderByImportPositionAscIdAsc(requireNotNull(user.id))

        assertNotNull(gameWeaponRepository.findByKey("wolfsgravestone"))
        assertEquals(snapshot.weapons.size, storedCopies.size)
        assertEquals(
            GoodKeyNormalizer.normalize(snapshot.weapons.first().key),
            storedCopies.first().weapon.key,
        )
        assertNotNull(
            imageUrlRegistry.weaponLink("wolfsgravestone", WeaponImageType.ICON)?.effectiveUrl,
        )
    }

    @Test
    fun `image management includes weapon image links`() {
        mockMvc.perform(get("/admin/images").param("type", "weapon").param("query", "Rust"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("/admin/images/weapons/rust")))
    }

    @Test
    fun `Irminsul bridge saves a captured GOOD snapshot for the local user`() {
        val result = integrationService.receiveSnapshot(
            Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT),
        )
        val user = assertNotNull(
            userRepository.findByEmailIgnoreCase(DesktopUserProvider.DESKTOP_EMAIL),
        )

        assertEquals(IrminsulCaptureState.COMPLETE, result.state)
        assertEquals(81, snapshotStore.current(requireNotNull(user.id))?.characters?.size)
    }

    @Test
    fun `Irminsul bridge keeps an active capture session live after saving`() {
        ReflectionTestUtils.setField(integrationService, "activeSession", "live-test")
        try {
            val result = integrationService.receiveSnapshot(
                Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT),
            )

            assertEquals(IrminsulCaptureState.LIVE, result.state)
            assertTrue(result.state.active)
        } finally {
            integrationService.receiveStatus(
                IrminsulStatusEvent(state = "stopped", message = "Test capture stopped."),
            )
        }
    }
}
