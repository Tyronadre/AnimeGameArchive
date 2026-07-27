package de.tyro.genshinapp

import de.tyro.genshinapp.service.GoodImportServiceTest
import de.tyro.genshinapp.repository.MaterialRepository
import de.tyro.genshinapp.repository.MaterialSourceRepository
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpSession
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "spring.docker.compose.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:missing-materials;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
    ],
)
@AutoConfigureMockMvc
class MissingMaterialsIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val materialRepository: MaterialRepository,
    private val materialSourceRepository: MaterialSourceRepository,
) {
    @Test
    fun `materials are grouped on a top-level live page`() {
        mockMvc.perform(
            post("/registration")
                .with(csrf())
                .param("name", "Material Tester")
                .param("email", "materials@example.com")
                .param("password", "long-enough-password")
                .param("passwordConfirmation", "long-enough-password"),
        ).andExpect(status().is3xxRedirection)

        val login = mockMvc.perform(
            post("/login")
                .with(csrf())
                .param("email", "materials@example.com")
                .param("password", "long-enough-password"),
        ).andExpect(status().is3xxRedirection).andReturn()
        val session = login.request.getSession(false) as MockHttpSession
        val export = MockMultipartFile(
            "file",
            "genshin-export.json",
            "application/json",
            Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT),
        )
        mockMvc.perform(
            multipart("/inventory/upload").file(export).session(session).with(csrf()),
        ).andExpect(status().is3xxRedirection)

        mockMvc.perform(get("/inventory/missing").session(session))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/materials"))

        mockMvc.perform(get("/materials").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Materials by source")))
            .andExpect(content().string(containsString("Forsaken Rift")))
            .andExpect(content().string(containsString("Lightless Capital")))
            .andExpect(content().string(containsString("Teachings of Freedom")))
            .andExpect(content().string(containsString("Guide to Freedom")))
            .andExpect(content().string(containsString("Philosophies of Freedom")))
            .andExpect(content().string(containsString("Philosophies of Vagrancy")))
            .andExpect(content().string(containsString("Grouped by source")))
            .andExpect(content().string(containsString("Keep is reserved for exact-item character needs")))
            .andExpect(content().string(containsString("Free to use")))
            .andExpect(content().string(containsString("Can make")))
            .andExpect(content().string(containsString("Still need")))
            .andExpect(content().string(containsString("data-snapshot-revision=")))
            .andExpect(content().string(containsString("/materials/api/revision")))
            .andExpect(content().string(containsString("new DOMParser()")))
            .andExpect(content().string(containsString("openMaterialDialog(link.href)")))
            .andExpect(content().string(containsString("event.preventDefault()")))
            .andExpect(content().string(containsString("data-material-item=\"true\"")))
            .andExpect(content().string(containsString("/materials/popup?materialId=")))
            .andExpect(content().string(not(containsString("showModal()"))))
            .andExpect(content().string(not(containsString("window.location.assign(materialUrl)"))))
            .andExpect(content().string(not(containsString("window.location.reload()"))))

        mockMvc.perform(get("/materials/popup").param("materialId", "104303").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("material-dialog-overlay")))
            .andExpect(content().string(containsString("Philosophies of Freedom")))

        mockMvc.perform(get("/materials/api/revision").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("\"revision\":")))

        val freedomGuide = assertNotNull(materialRepository.findByGameId(104302))
        assertEquals("TALENT_BOOK", freedomGuide.type)
        assertEquals("talent:freedom", freedomGuide.craftingFamily)
        assertEquals(1, freedomGuide.craftingTier)

        val brilliantDiamond = assertNotNull(materialRepository.findByGameId(104102))
        assertEquals("OTHER", brilliantDiamond.type)
        assertNull(brilliantDiamond.craftingFamily)
        assertNull(brilliantDiamond.craftingTier)
        assertNull(brilliantDiamond.conversionGroup)

        val talentDomains = materialSourceRepository
            .findAllBySourceTypeInOrderByDisplayOrderAscNameAsc(listOf("TALENT_DOMAIN"))
        assertEquals(7, talentDomains.size)
        assertTrue(talentDomains.first { it.name == "Forsaken Rift" }.materials.size == 9)
    }
}
