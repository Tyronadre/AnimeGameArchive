package de.tyro.genshinapp.startup

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import de.tyro.genshinapp.security.AppUserPrincipal
import de.tyro.genshinapp.service.account.UserService
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:startup-loading;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false",
    "spring.docker.compose.enabled=false",
    "genshin.content.cache-directory=build/test-startup-loading-cache",
    "genshin.content.hoyolab-wiki-enabled=false",
])
@AutoConfigureMockMvc
class StartupLoadingIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val userService: UserService,
) {
    @Test
    fun `first browser document is public but the actual dashboard remains protected`() {
        mvc.perform(get("/").accept(MediaType.TEXT_HTML))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Lumine running in place")))

        mvc.perform(get("/").accept(MediaType.TEXT_HTML).header(StartupPageFilter.READY_HEADER, "true"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("http://localhost/login"))
        mvc.perform(get("/").param("direct", "1").accept(MediaType.TEXT_HTML))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("http://localhost/login"))
    }

    @Test
    fun `startup script and animation can load before authentication`() {
        mvc.perform(get("/startup/loader.js"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString(StartupPageFilter.READY_HEADER)))
        mvc.perform(get("/startup/lumine-sprint.png"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.IMAGE_PNG))
        mvc.perform(get("/startup/lumine-sprint-poster.png"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.IMAGE_PNG))
    }

    @Test
    fun `authenticated archive renders the companion and serves its assets`() {
        val account = userService.register("Companion Tester", "companion@example.com", "companion-password")
        val principal = AppUserPrincipal(requireNotNull(account.id), account.name, account.email, account.passwordHash)
        mvc.perform(get("/").header(StartupPageFilter.READY_HEADER, "true").with(user(principal)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("data-companion-toggle")))
            .andExpect(content().string(containsString("/js/companion.js")))

        listOf("/js/companion.js", "/css/companion.css", "/companion/lumine-idle.png", "/companion/lumine-idle-poster.png").forEach {
            mvc.perform(get(it).with(user(principal))).andExpect(status().isOk)
        }
    }
}
