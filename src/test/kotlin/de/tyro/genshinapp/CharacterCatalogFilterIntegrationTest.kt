package de.tyro.genshinapp

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:character-catalog-filters;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.messages.fallback-to-system-locale=false",
        "genshin.content.cache-directory=build/test-cache/character-catalog-filters",
        "genshin.content.hoyolab-wiki-enabled=false",
    ],
)
@ActiveProfiles("desktop")
@AutoConfigureMockMvc
class CharacterCatalogFilterIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {
    @Test
    fun `character catalog renders searchable filter metadata and Wikia icons`() {
        mockMvc.perform(get("/characters").param("query", "Albedo"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("value=\"Albedo\"")))
            .andExpect(content().string(containsString("/js/character-catalog.js")))
            .andExpect(content().string(containsString("data-element-filter=\"anemo\"")))
            .andExpect(content().string(containsString("Element_Anemo.png")))
            .andExpect(content().string(containsString("data-weapon-filter=\"sword\"")))
            .andExpect(content().string(containsString("Icon_Sword.png")))
            .andExpect(content().string(containsString("data-ownership-filter=\"owned\"")))
            .andExpect(content().string(containsString("data-rarity-filter=\"4\"")))
            .andExpect(content().string(containsString("data-result-template=\"RESULT_COUNT results\"")))
    }
}
