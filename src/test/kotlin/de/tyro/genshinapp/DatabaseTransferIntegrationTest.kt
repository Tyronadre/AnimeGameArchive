package de.tyro.genshinapp

import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.configuration.DesktopUserProvider
import de.tyro.genshinapp.model.CharacterProgress
import de.tyro.genshinapp.repository.UserRepository
import de.tyro.genshinapp.service.planning.CharacterTargetService
import de.tyro.genshinapp.service.player.GoodImportService
import de.tyro.genshinapp.service.player.GoodImportServiceTest
import de.tyro.genshinapp.service.player.PlayerSnapshotStore
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:database-transfer;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.messages.fallback-to-system-locale=false",
        "genshin.content.cache-directory=build/test-database-transfer-cache",
        "genshin.content.hoyolab-wiki-enabled=false",
    ],
)
@ActiveProfiles("desktop")
@AutoConfigureMockMvc
class DatabaseTransferIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val goodImportService: GoodImportService,
    private val snapshotStore: PlayerSnapshotStore,
    private val targetService: CharacterTargetService,
    private val userRepository: UserRepository,
) {
    @Test
    fun `database page imports by replacement and exports the current database as GOOD`() {
        mockMvc.perform(get("/database"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Database")))
            .andExpect(content().string(containsString("/database/import")))

        val original = MockMultipartFile(
            "file",
            "genshin-export.json",
            "application/json",
            Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT),
        )
        val userId = requireNotNull(
            userRepository.findByEmailIgnoreCase(DesktopUserProvider.DESKTOP_EMAIL)?.id,
        )
        mockMvc.perform(multipart("/inventory/upload").file(original).with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/inventory/items"))
        snapshotStore.updateInventoryAmount(userId, "heroswit", 999)
        targetService.save(
            userId,
            "amber",
            CharacterProgress(
                owned = true,
                level = 79,
                ascension = 5,
                constellation = 4,
                normalTalent = 7,
                skillTalent = 8,
                burstTalent = 9,
            ),
        )

        val response = mockMvc.perform(get("/database/export"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Disposition", containsString("attachment")))
            .andExpect(header().string("Content-Disposition", containsString("good-v3.json")))
            .andReturn().response
        val document = objectMapper.readTree(response.contentAsByteArray)
        assertEquals("GOOD", document.path("format").asText())
        assertEquals(3, document.path("version").asInt())
        assertEquals(999L, document.path("materials").path("HerosWit").asLong())
        assertEquals("RecurveBow", document.path("weapons").first().path("key").asText())
        assertEquals("MaidenBeloved", document.path("artifacts").first().path("setKey").asText())
        assertEquals(
            5.8,
            document.path("artifacts").first().path("substats").first()
                .path("initialValue").asDouble(),
        )
        assertEquals(
            79,
            document.path("characters").first { it.path("key").asText() == "Amber" }
                .path("level").asInt(),
        )
        val roundTrip = goodImportService.parse(response.contentAsByteArray)
        assertEquals(999L, roundTrip.inventory["heroswit"])
        assertEquals(document.path("artifacts").size(), roundTrip.artifacts.size)
        assertEquals(document.path("weapons").size(), roundTrip.weapons.size)

        val replacement = MockMultipartFile(
            "file",
            "replacement.json",
            "application/json",
            """
            {
              "format": "GOOD",
              "version": 3,
              "source": "replacement",
              "characters": [{
                "key": "Lisa",
                "level": 40,
                "constellation": 1,
                "ascension": 2,
                "talent": {"auto": 2, "skill": 4, "burst": 4}
              }],
              "materials": {"Mora": 123},
              "artifacts": [],
              "weapons": []
            }
            """.trimIndent().toByteArray(),
        )
        mockMvc.perform(multipart("/database/import").file(replacement).with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/database"))

        val stored = assertNotNull(snapshotStore.current(userId))
        assertEquals(listOf("Lisa"), stored.characters.map { it.key })
        assertEquals(mapOf("mora" to 123L), stored.inventory)
        assertEquals(0, stored.artifacts.size)
        assertEquals(0, stored.weapons.size)
        assertNull(targetService.find(userId, "amber")?.currentLevel)
        assertNull(targetService.find(userId, "amber")?.owned)
    }
}
