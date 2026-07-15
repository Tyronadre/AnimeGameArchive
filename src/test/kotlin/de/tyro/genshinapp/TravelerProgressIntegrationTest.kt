package de.tyro.genshinapp

import de.tyro.genshinapp.configuration.DesktopUserProvider
import de.tyro.genshinapp.model.TravelerElement
import de.tyro.genshinapp.repository.UserRepository
import de.tyro.genshinapp.service.CharacterTargetService
import de.tyro.genshinapp.service.TravelerService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:traveler-progress;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "genshin.content.cache-directory=build/test-traveler-progress-cache",
    ],
)
@ActiveProfiles("desktop")
@AutoConfigureMockMvc
class TravelerProgressIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val userRepository: UserRepository,
    private val characterTargetService: CharacterTargetService,
    private val travelerService: TravelerService,
) {
    @Test
    fun `both character progress save paths retain edited current values`() {
        mockMvc.perform(get("/characters/kamisatoayaka"))
            .andExpect(status().isOk)
        val user = assertNotNull(
            userRepository.findByEmailIgnoreCase(DesktopUserProvider.DESKTOP_EMAIL),
        )
        val userId = requireNotNull(user.id)

        mockMvc.perform(
            post("/characters/kamisatoayaka/progress")
                .with(csrf())
                .param("level", "40")
                .param("targetLevel", "80")
                .param("ascension", "2")
                .param("targetAscension", "6")
                .param("constellation", "1")
                .param("normalTalent", "4")
                .param("skillTalent", "5")
                .param("burstTalent", "6")
                .param("targetNormalTalent", "9")
                .param("targetSkillTalent", "9")
                .param("targetBurstTalent", "9"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.normalTalent").value(4))
            .andExpect(jsonPath("$.skillTalent").value(5))
            .andExpect(jsonPath("$.burstTalent").value(6))

        characterTargetService.find(userId, "kamisatoayaka").let { saved ->
            assertNotNull(saved)
            assertEquals(true, saved.owned)
            assertEquals(40, saved.currentLevel)
            assertEquals(4, saved.currentNormalTalent)
        }

        mockMvc.perform(
            post("/characters/kamisatoayaka")
                .with(csrf())
                .param("level", "50")
                .param("targetLevel", "90")
                .param("ascension", "3")
                .param("targetAscension", "6")
                .param("constellation", "2")
                .param("normalTalent", "5")
                .param("skillTalent", "6")
                .param("burstTalent", "7")
                .param("targetNormalTalent", "10")
                .param("targetSkillTalent", "10")
                .param("targetBurstTalent", "10"),
        )
            .andExpect(status().is3xxRedirection)

        characterTargetService.find(userId, "kamisatoayaka").let { saved ->
            assertNotNull(saved)
            assertEquals(true, saved.owned)
            assertEquals(50, saved.currentLevel)
            assertEquals(5, saved.currentNormalTalent)
            assertEquals(10, saved.targetNormalTalent)
        }
    }

    @Test
    fun `saves independent talent levels for every traveler element`() {
        mockMvc.perform(get("/characters/traveler"))
            .andExpect(status().isOk)
        val user = assertNotNull(
            userRepository.findByEmailIgnoreCase(DesktopUserProvider.DESKTOP_EMAIL),
        )

        TravelerElement.entries.forEachIndexed { index, element ->
            val normalTalent = index + 1
            val skillTalent = index + 2
            val burstTalent = index + 3

            mockMvc.perform(
                post("/characters/traveler/selection")
                    .with(csrf())
                    .param("element", element.key),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.element").value(element.key))

            mockMvc.perform(
                post("/characters/traveler/progress")
                    .with(csrf())
                    .param("owned", "true")
                    .param("level", "80")
                    .param("targetLevel", "90")
                    .param("ascension", "5")
                    .param("targetAscension", "6")
                    .param("constellation", index.toString())
                    .param("normalTalent", normalTalent.toString())
                    .param("skillTalent", skillTalent.toString())
                    .param("burstTalent", burstTalent.toString())
                    .param("targetNormalTalent", "10")
                    .param("targetSkillTalent", "10")
                    .param("targetBurstTalent", "10"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.normalTalent").value(normalTalent))
                .andExpect(jsonPath("$.skillTalent").value(skillTalent))
                .andExpect(jsonPath("$.burstTalent").value(burstTalent))
        }

        TravelerElement.entries.forEachIndexed { index, element ->
            val saved = assertNotNull(travelerService.progress(requireNotNull(user.id), element))
            assertEquals(index + 1, saved.normalTalent)
            assertEquals(index + 2, saved.skillTalent)
            assertEquals(index + 3, saved.burstTalent)
            assertEquals(index, saved.constellation)
        }
    }
}
