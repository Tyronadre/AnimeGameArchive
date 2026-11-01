package de.tyro.genshinapp.presentation

import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterImageType
import de.tyro.genshinapp.security.AppUserPrincipal
import de.tyro.genshinapp.service.account.UserService
import de.tyro.genshinapp.service.catalog.CharacterCatalogStore
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.nio.file.Files
import java.nio.file.Path

@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:character-stage;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "spring.docker.compose.enabled=false",
    "genshin.content.cache-directory=build/test-character-stage-cache", "genshin.content.hoyolab-wiki-enabled=false",
    "genshin.presentation.model-directory=",
])
@AutoConfigureMockMvc
class CharacterStageIntegrationTest @Autowired constructor(
    private val mvc: MockMvc, private val userService: UserService, private val store: CharacterCatalogStore,
) {
    @Test fun `model endpoints retain authentication and portable model assets work under a context path`() {
        mvc.perform(get("/api/character-stages/lumine")).andExpect(status().is3xxRedirection)
        val account = userService.register("Stage Asset", "stage-asset@example.com", "stage-password")
        val principal = AppUserPrincipal(requireNotNull(account.id), account.name, account.email, account.passwordHash)
        mvc.perform(get("/archive/api/character-stages/travelerlumine").contextPath("/archive").with(user(principal)))
            .andExpect(status().isOk).andExpect(jsonPath("$.available").value(true))
            .andExpect(jsonPath("$.modelUrl").value("/archive/media/character-stages/lumine/files/lumine.pmx"))
        mvc.perform(get("/media/character-stages/lumine/files/lumine.pmx").with(user(principal)))
            .andExpect(status().isOk)
        mvc.perform(get("/api/character-stages/nefer").with(user(principal)))
            .andExpect(status().isOk).andExpect(jsonPath("$.available").value(false))
        mvc.perform(get("/media/character-stages/lumine/files/private.txt").with(user(principal)))
            .andExpect(status().isNotFound)
    }

    @Test fun `character detail renders the stage while preserving usable planning forms`() {
        store.saveCharacter(CharacterDefinition(
            key="stagefixture", id=777777, name="Stage Fixture", title="A new adventure", description="A character stage",
            weapon="Sword", rarity=5, birthday=null, element="Hydro", affiliation=null, region="Fontaine", constellation="Stars",
            ascensionStatType=null, imageUrls=mapOf(CharacterImageType.WISH to "/startup/lumine-sprint-poster.png"),
            remoteImageUrls=emptyMap(), ascensionCosts=emptyMap(), talentCosts=emptyMap(),
        ))
        val account = userService.register("Stage Page", "stage-page@example.com", "stage-password")
        val principal = AppUserPrincipal(requireNotNull(account.id), account.name, account.email, account.passwordHash)
        val result = mvc.perform(get("/characters/stagefixture").with(user(principal)))
            .andExpect(status().isOk).andExpect(content().string(containsString("data-character-stage")))
            .andExpect(content().string(containsString("/api/character-stages/stagefixture")))
            .andExpect(content().string(containsString("character-progress-form")))
            .andExpect(content().string(containsString("Stage Fixture"))).andReturn()
        Files.createDirectories(Path.of(".tmp"))
        Files.writeString(Path.of(".tmp/character-stage-rendered.html"), result.response.contentAsString)
    }
}
