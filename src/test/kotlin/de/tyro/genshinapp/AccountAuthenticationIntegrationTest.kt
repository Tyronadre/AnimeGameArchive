package de.tyro.genshinapp

import de.tyro.genshinapp.service.planning.SavedWeaponTarget
import de.tyro.genshinapp.repository.UserRepository
import de.tyro.genshinapp.service.artifact.ArtifactOptimizerCustomProfileService
import de.tyro.genshinapp.service.artifact.ArtifactOptimizerProfileService
import de.tyro.genshinapp.service.artifact.ArtifactOptimizerSharingService
import de.tyro.genshinapp.service.planning.CharacterWeaponTargetService
import de.tyro.genshinapp.service.planning.CharacterTargetService
import de.tyro.genshinapp.service.planning.DashboardGoalService
import de.tyro.genshinapp.service.planning.DashboardGoalType
import de.tyro.genshinapp.service.player.GoodImportServiceTest
import de.tyro.genshinapp.service.account.UserService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated
import org.springframework.mock.web.MockMultipartFile
import org.springframework.mock.web.MockHttpSession
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
        "spring.datasource.url=jdbc:h2:mem:authentication;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "genshin.content.static-import-enabled=false",
    ],
)
@AutoConfigureMockMvc
class AccountAuthenticationIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val userRepository: UserRepository,
    private val userService: UserService,
    private val targetService: CharacterTargetService,
    private val optimizerCustomProfileService: ArtifactOptimizerCustomProfileService,
    private val optimizerProfileService: ArtifactOptimizerProfileService,
    private val optimizerSharingService: ArtifactOptimizerSharingService,
    private val weaponTargetService: CharacterWeaponTargetService,
    private val dashboardGoalService: DashboardGoalService,
    private val passwordEncoder: PasswordEncoder,
) {
    @Test
    fun `artifact quality alternatives projections and shared plans render and survive saving`() {
        val account = userService.register("Quality Tester", "artifact-quality@example.com", "quality-password")
        val login = mockMvc.perform(post("/login").with(csrf())
            .param("email", account.email).param("password", "quality-password"))
            .andExpect(authenticated()).andReturn()
        val session = login.request.getSession(false) as MockHttpSession
        val inventory = """
            {"format":"GOOD","version":3,"source":"quality-test",
             "characters":[{"key":"Tartaglia","level":90,"ascension":6,"constellation":0,
                            "talent":{"auto":1,"skill":1,"burst":1}}],"materials":{},"weapons":[],
             "artifacts":[
               {"setKey":"GladiatorsFinale","slotKey":"flower","rarity":5,"level":0,"mainStatKey":"hp","location":"Tartaglia",
                "substats":[{"key":"critRate_","value":3.89},{"key":"critDMG_","value":7.77},{"key":"atk_","value":5.83}]},
               {"setKey":"HeartOfDepth","slotKey":"plume","rarity":5,"level":20,"mainStatKey":"atk","location":"Tartaglia",
                "substats":[{"key":"critRate_","value":3.89},{"key":"critDMG_","value":7.77},{"key":"atk_","value":5.83},{"key":"enerRech_","value":6.48}]},
               {"setKey":"GladiatorsFinale","slotKey":"sands","rarity":5,"level":20,"mainStatKey":"atk_","location":"Tartaglia",
                "substats":[{"key":"critRate_","value":3.89},{"key":"critDMG_","value":7.77},{"key":"eleMas","value":23.31},{"key":"enerRech_","value":6.48}]},
               {"setKey":"GladiatorsFinale","slotKey":"goblet","rarity":5,"level":20,"mainStatKey":"hydro_dmg_","location":"Tartaglia",
                "substats":[{"key":"critRate_","value":3.89},{"key":"critDMG_","value":7.77},{"key":"atk_","value":5.83},{"key":"enerRech_","value":6.48}]},
               {"setKey":"GladiatorsFinale","slotKey":"circlet","rarity":5,"level":20,"mainStatKey":"critRate_","location":"Tartaglia",
                "substats":[{"key":"atk","value":19.45},{"key":"critDMG_","value":7.77},{"key":"atk_","value":5.83},{"key":"enerRech_","value":6.48}]}
             ]}
        """.trimIndent()
        mockMvc.perform(multipart("/inventory/upload")
            .file(MockMultipartFile("file", "quality.json", "application/json", inventory.toByteArray()))
            .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection)
        mockMvc.perform(post("/inventory/artifact-optimizer/profile").session(session).with(csrf())
            .param("character", "Tartaglia").param("optimizerCharacter", "Tartaglia").param("profile", "custom-new")
            .param("customProfileName", "Quality Plan").param("customTargets", "true")
            .param("sandsMain", "atk_").param("gobletMain", "hydro_dmg_")
            .param("circletMain", "critRate_|critDMG_|eleMas")
            .param("priorityStats", "critRate_", "critDMG_", "atk_", "enerRech_")
            .param("setMode", "custom").param("firstSet", "GladiatorsFinale").param("firstSetCount", "4"))
            .andExpect(status().is3xxRedirection)
        val saved = requireNotNull(optimizerProfileService.find(account.id!!, "Tartaglia"))
        val named = optimizerCustomProfileService.findAll(account.id!!, "Tartaglia").single()
        assertEquals("critRate_|critDMG_|eleMas", saved.targets.mainStats["circlet"])
        assertEquals(saved.targets.mainStats, named.targets.mainStats)
        mockMvc.perform(get("/inventory/artifact-optimizer").session(session)
            .param("character", "Tartaglia").param("lang", "en"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("average substat quality")))
            .andExpect(content().string(containsString("CRIT Rate / CRIT DMG / Elemental Mastery")))
            .andExpect(content().string(containsString("Expected quality at +20")))
            .andExpect(content().string(containsString("Target set")))
            .andExpect(content().string(containsString("Off-piece")))
        val shared = optimizerSharingService.create(account.id!!, named.profile, named.targets, named.setSelection)
        val loadedShare = requireNotNull(optimizerSharingService.find(shared.token))
        assertEquals(named.targets.mainStats, loadedShare.targets.mainStats)
        mockMvc.perform(get("/inventory/artifact-optimizer").session(session)
            .param("character", "Tartaglia").param("shared", shared.token).param("lang", "en"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Shared configuration preview")))
            .andExpect(content().string(containsString("CRIT Rate / CRIT DMG / Elemental Mastery")))
    }

    @Test
    fun `english is the default and german can be selected persistently`() {
        mockMvc.perform(get("/login"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Welcome back.")))
            .andExpect(content().string(containsString("<html lang=\"en\"")))

        val germanResponse = mockMvc.perform(
            get("/login").param("lang", "de"),
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Willkommen zurück.")))
            .andExpect(content().string(containsString("<html lang=\"de\"")))
            .andReturn()
            .response
        val languageCookie = assertNotNull(
            germanResponse.getCookie("genshin-language"),
        )

        mockMvc.perform(get("/login").cookie(languageCookie))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Willkommen zurück.")))

        mockMvc.perform(
            post("/registration")
                .cookie(languageCookie)
                .with(csrf())
                .param("name", "Traveler")
                .param("email", "traveler-language@example.com")
                .param("password", "first-password")
                .param("passwordConfirmation", "different-password"),
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Die Passwörter stimmen nicht überein.")))
    }

    @Test
    fun `registration creates an account that can log in`() {
        mockMvc.perform(get("/"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("http://localhost/login"))

        mockMvc.perform(get("/login"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("name=\"_csrf\"")))

        mockMvc.perform(
            post("/registration")
                .with(csrf())
                .param("name", "Traveler")
                .param("email", "traveler@example.com")
                .param("password", "long-enough-password")
                .param("passwordConfirmation", "long-enough-password"),
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login"))

        val account = requireNotNull(userRepository.findByEmailIgnoreCase("traveler@example.com"))
        check(passwordEncoder.matches("long-enough-password", account.passwordHash))

        val loginResult = mockMvc.perform(
            post("/login")
                .with(csrf())
                .param("email", "traveler@example.com")
                .param("password", "long-enough-password"),
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/"))
            .andExpect(authenticated().withUsername("traveler@example.com"))
            .andReturn()

        val session = loginResult.request.getSession(false) as MockHttpSession
        mockMvc.perform(
            get("/inventory/items")
                .session(session),
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Traveler")))

        val goodExport = MockMultipartFile(
            "file",
            "genshin-export.json",
            "application/json",
            Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT),
        )
        mockMvc.perform(
            multipart("/inventory/upload")
                .file(goodExport)
                .session(session)
                .with(csrf()),
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/inventory/items"))

        val automaticDashboard = mockMvc.perform(get("/").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("What is worth doing today?")))
            .andExpect(content().string(containsString("Automatic plan")))
            .andExpect(content().string(containsString("Secure weekly rewards")))
            .andExpect(content().string(containsString("Continue without resin")))
            .andExpect(content().string(containsString("data-free-task")))
            .andExpect(content().string(containsString("name=\"characterGoals\"")))
            .andExpect(content().string(containsString("name=\"artifactGoals\"")))
            .andExpect(content().string(containsString("data-element-filter=\"all\"")))
            .andExpect(content().string(containsString("data-character-search=\"")))
            .andExpect(content().string(containsString("data-character-element=\"")))
            .andReturn()
            .response
            .contentAsString
        val automaticTaskCount =
            Regex("data-(resin|free)-task=").findAll(automaticDashboard).count()
        assertTrue(
            automaticTaskCount >= 10,
            "Expected at least 10 automatic dashboard tasks, got $automaticTaskCount",
        )
        assertTrue(automaticDashboard.contains("dashboard-material-progress"))

        mockMvc.perform(
            post("/inventory/items/heroswit")
                .session(session)
                .with(csrf())
                .param("amount", "999"),
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/inventory/items"))

        mockMvc.perform(get("/api/dashboard/recent-activity").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("\"revision\":")))
            .andExpect(content().string(containsString("\"activities\"")))
            .andExpect(content().string(containsString("Hero")))
            .andExpect(content().string(containsString("999 total")))

        mockMvc.perform(get("/api/dashboard/plan").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("\"weekly\"")))
            .andExpect(content().string(containsString("\"resin\"")))
            .andExpect(content().string(containsString("\"free\"")))
            .andExpect(content().string(containsString("\"progressText\"")))
            .andExpect(content().string(containsString("Collected")))

        mockMvc.perform(
            post("/goals")
                .session(session)
                .with(csrf())
                .param("characterGoals", "Aloy")
                .param("artifactGoals", "Tartaglia"),
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/"))

        val dashboardGoals = dashboardGoalService.findAll(account.id!!)
        assertEquals(2, dashboardGoals.size)
        assertEquals(
            setOf(DashboardGoalType.CHARACTER, DashboardGoalType.ARTIFACTS),
            dashboardGoals.mapTo(mutableSetOf()) { it.type },
        )

        mockMvc.perform(get("/").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Personal focus")))
            .andExpect(content().string(containsString("Three decisions. No checklist chaos.")))
            .andExpect(content().string(containsString("Aloy")))
            .andExpect(content().string(containsString("Tartaglia")))
            .andExpect(
                content().string(
                    containsString("/inventory/artifact-optimizer?character=tartaglia"),
                ),
            )

        mockMvc.perform(get("/").param("lang", "de").session(session))
            .andExpect(status().isOk)
            .andExpect(
                content().string(
                    containsString("Was lohnt sich heute?"),
                ),
            )

        mockMvc.perform(get("/inventory/artifacts").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("class=\"artifact-icon rarity-5\"")))
            .andExpect(content().string(containsString("src=\"/media/artifacts/")))
            .andExpect(content().string(containsString("Define a new artifact")))
            .andExpect(content().string(containsString("Manage artifact")))
            .andExpect(content().string(containsString("id=\"artifact-create-dialog\"")))
            .andExpect(content().string(containsString("id=\"artifact-change-dialog\"")))
            .andExpect(content().string(containsString("data-artifact-value-mode=\"rolls\"")))
            .andExpect(content().string(containsString("class=\"artifact-total-rolls\"")))
            .andExpect(content().string(containsString("critRate_: [2.72, 3.11, 3.5, 3.89]")))
            .andExpect(content().string(containsString("name=\"artifactIndex\"")))

        mockMvc.perform(
            post("/inventory/artifacts/update")
                .session(session)
                .with(csrf())
                .param("artifactIndex", "0")
                .param("setKey", "MaidenBeloved")
                .param("slotKey", "flower")
                .param("rarity", "5")
                .param("level", "16")
                .param("mainStatKey", "hp")
                .param("substatKeys", "atk_", "critRate_", "atk", "hp_")
                .param("substatValues", "16.9", "3.5", "64", "5.3"),
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/inventory/artifacts"))

        mockMvc.perform(
            post("/inventory/artifacts/create")
                .session(session)
                .with(csrf())
                .param("setKey", "HeartOfDepth")
                .param("slotKey", "flower")
                .param("rarity", "5")
                .param("level", "0")
                .param("mainStatKey", "hp")
                .param("substatKeys", "critRate_", "critDMG_")
                .param("substatValues", "3.89", "7.77")
                .param("totalRolls", "2"),
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/inventory/artifacts"))

        mockMvc.perform(
            post("/inventory/artifacts/assign")
                .session(session)
                .with(csrf())
                .param("artifactIndex", "1177")
                .param("location", "ShikanoinHeizou"),
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/inventory/artifacts?character=ShikanoinHeizou"))

        mockMvc.perform(get("/inventory/weapons").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("class=\"weapon-icon\"")))
            .andExpect(content().string(containsString("src=\"/media/weapons/")))

        mockMvc.perform(get("/inventory/artifact-optimizer").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Artifact Optimizer")))
            .andExpect(content().string(containsString("How hard is the next upgrade?")))
            .andExpect(content().string(containsString("Current artifacts")))
            .andExpect(content().string(containsString("Build targets")))
            .andExpect(content().string(containsString("id=\"optimizer-character-select\"")))
            .andExpect(content().string(containsString("name=\"sandsMain\"")))
            .andExpect(content().string(containsString("name=\"statBonusValues\"")))
            .andExpect(content().string(containsString("id=\"optimizer-priority-selected\"")))
            .andExpect(content().string(containsString("name=\"priorityStats\"")))
            .andExpect(content().string(containsString("name=\"priorityMinimums\"")))
            .andExpect(content().string(containsString("name=\"priorityMaximums\"")))
            .andExpect(content().string(containsString("value=\"100.0\"")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("How the estimate works"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("name=\"flowerMain\""))))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("name=\"plumeMain\""))))

        mockMvc.perform(
            get("/inventory/artifact-optimizer")
                .param("character", "Furina")
                .param("profile", "attack")
                .param("customTargets", "true")
                .param("sandsMain", "hp_")
                .param("gobletMain", "hydro_dmg_")
                .param("circletMain", "critRate_")
                .param("priorityStats", "critRate_", "critDMG_", "hp_")
                .param("priorityMinimums", "", "", "")
                .param("priorityMaximums", "100", "", "")
                .session(session),
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("name=\"sandsMain\"")))
            .andExpect(
                content().string(
                    matchesPattern(
                        "(?s).*<option value=\"hp_\"\\s+selected=\"selected\">HP %</option>.*",
                    ),
                ),
            )
            .andExpect(
                content().string(
                    matchesPattern(
                        "(?s).*name=\"priorityStats\"[^>]*value=\"critDMG_\".*",
                    ),
                ),
            )

        mockMvc.perform(
            get("/inventory/artifact-optimizer")
                .param("character", "Tartaglia")
                .session(session),
        )
            .andExpect(status().isOk)
            .andExpect(
                content().string(
                    org.hamcrest.Matchers.not(containsString("name=\"weaponTargetLevel\"")),
                ),
            )

        targetService.saveAdditionalStats(
            account.id!!,
            "Tartaglia",
            mapOf("eleMas" to 120.0),
        )
        mockMvc.perform(
            post("/inventory/artifact-optimizer/profile")
                .session(session)
                .with(csrf())
                .param("character", "Tartaglia")
                .param("profile", "custom-new")
                .param("customProfileName", "Tartaglia DPS")
                .param("customTargets", "true")
                .param("sandsMain", "hp_")
                .param("gobletMain", "hydro_dmg_")
                .param("circletMain", "critRate_")
                .param("priorityStats", "critRate_", "critDMG_", "hp_", "enerRech_")
                .param("priorityMinimums", "70", "", "", "150")
                .param("priorityMaximums", "100", "220", "", "180")
                .param("statBonusKeys", "critRate_", "critDMG_", "enerRech_")
                .param("statBonusValues", "12.5", "18", "-10")
                .param("setMode", "custom")
                .param("firstSet", "HeartOfDepth")
                .param("firstSetCount", "4"),
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/inventory/artifact-optimizer?character=Tartaglia"))

        val savedOptimizerProfile = requireNotNull(
            optimizerProfileService.find(account.id!!, "Tartaglia"),
        )
        assertEquals("attack", savedOptimizerProfile.profile.key)
        assertEquals("hp_", savedOptimizerProfile.targets.mainStats["sands"])
        assertEquals(
            setOf("critDMG_", "critRate_", "hp_", "enerRech_"),
            savedOptimizerProfile.targets.substatKeys,
        )
        assertEquals(
            listOf("critRate_", "critDMG_", "hp_", "enerRech_"),
            savedOptimizerProfile.targets.substatPriorities,
        )
        assertEquals(70.0, savedOptimizerProfile.targets.minimumTargets["critRate_"])
        assertEquals(150.0, savedOptimizerProfile.targets.minimumTargets["enerRech_"])
        assertEquals(100.0, savedOptimizerProfile.targets.maximumTargets["critRate_"])
        assertEquals(220.0, savedOptimizerProfile.targets.maximumTargets["critDMG_"])
        assertEquals(180.0, savedOptimizerProfile.targets.maximumTargets["enerRech_"])
        assertEquals(12.5, savedOptimizerProfile.targets.additionalCritRate)
        assertEquals(18.0, savedOptimizerProfile.targets.additionalStats["critDMG_"])
        assertEquals(-10.0, savedOptimizerProfile.targets.additionalStats["enerRech_"])
        val synchronizedCharacterStats = requireNotNull(
            targetService.find(account.id!!, "Tartaglia"),
        ).additionalStats
        assertEquals(12.5, synchronizedCharacterStats["critRate_"])
        assertEquals(18.0, synchronizedCharacterStats["critDMG_"])
        assertEquals(-10.0, synchronizedCharacterStats["enerRech_"])
        assertEquals(120.0, synchronizedCharacterStats["eleMas"])
        assertEquals("custom", savedOptimizerProfile.setSelection.mode.key)
        assertEquals("heartofdepth", savedOptimizerProfile.setSelection.requirements.single().setKey)
        assertEquals(4, savedOptimizerProfile.setSelection.requirements.single().count)
        val customProfile = optimizerCustomProfileService.findAll(
            account.id!!,
            "Tartaglia",
        ).single()
        assertEquals("Tartaglia DPS", customProfile.name)
        assertEquals(customProfile.id, savedOptimizerProfile.customProfileId)
        assertNull(weaponTargetService.find(account.id!!, "Tartaglia"))

        mockMvc.perform(get("/characters/tartaglia").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Rust")))
            .andExpect(content().string(containsString("name=\"targetLevel\"")))
            .andExpect(content().string(containsString("Save weapon target")))
            .andExpect(
                content().string(
                    matchesPattern(
                        "(?s).*name=\"statBonusValues\"[^>]*value=\"12.5\".*",
                    ),
                ),
            )

        mockMvc.perform(
            post("/characters/tartaglia/weapon-target")
                .session(session)
                .with(csrf())
                .param("targetLevel", "90"),
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/characters/tartaglia"))

        val savedWeaponTarget = requireNotNull(weaponTargetService.find(account.id!!, "Tartaglia"))
        assertEquals("rust", savedWeaponTarget.weaponKey)
        assertEquals(90, savedWeaponTarget.targetLevel)

        val shareResult = mockMvc.perform(
            post("/inventory/artifact-optimizer/profile/share")
                .session(session)
                .with(csrf())
                .param("character", "Tartaglia")
                .param("profile", "custom-${customProfile.id}")
                .param("customTargets", "true")
                .param("sandsMain", "hp_")
                .param("gobletMain", "hydro_dmg_")
                .param("circletMain", "critRate_")
                .param("priorityStats", "critRate_", "critDMG_", "hp_", "enerRech_")
                .param("priorityMinimums", "70", "", "", "150")
                .param("priorityMaximums", "100", "220", "", "180")
                .param("statBonusKeys", "critRate_", "critDMG_", "enerRech_")
                .param("statBonusValues", "12.5", "18", "-10")
                .param("setMode", "custom")
                .param("firstSet", "HeartOfDepth")
                .param("firstSetCount", "4"),
        )
            .andExpect(status().is3xxRedirection)
            .andReturn()
        val sharedOptimizerLocation = requireNotNull(shareResult.response.redirectedUrl)
        val shareToken = sharedOptimizerLocation.substringAfter("shared=").substringBefore('&')
        val sharedConfiguration = requireNotNull(optimizerSharingService.find(shareToken))
        assertEquals("attack", sharedConfiguration.profile.key)
        assertEquals("hp_", sharedConfiguration.targets.mainStats["sands"])
        assertEquals(
            listOf("critRate_", "critDMG_", "hp_", "enerRech_"),
            sharedConfiguration.targets.substatPriorities,
        )
        assertEquals(70.0, sharedConfiguration.targets.minimumTargets["critRate_"])
        assertEquals(220.0, sharedConfiguration.targets.maximumTargets["critDMG_"])
        assertEquals(12.5, sharedConfiguration.targets.additionalCritRate)
        assertEquals(18.0, sharedConfiguration.targets.additionalStats["critDMG_"])
        assertEquals(-10.0, sharedConfiguration.targets.additionalStats["enerRech_"])

        mockMvc.perform(get(sharedOptimizerLocation).session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Shared configuration preview")))
            .andExpect(content().string(containsString("Copy link")))

        mockMvc.perform(
            post("/inventory/artifact-optimizer/profile")
                .session(session)
                .with(csrf())
                .param("character", "Tartaglia")
                .param("profile", "custom-new")
                .param("customProfileName", "Tartaglia ER")
                .param("customTargets", "true")
                .param("priorityStats", "enerRech_", "critRate_", "critDMG_")
                .param("priorityMinimums", "180", "", "")
                .param("priorityMaximums", "220", "100", "")
                .param("statBonusKeys", "critRate_", "critDMG_", "enerRech_")
                .param("statBonusValues", "0", "0", "0")
                .param("setMode", "current"),
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/inventory/artifact-optimizer?character=Tartaglia"))

        assertEquals(
            setOf("Tartaglia DPS", "Tartaglia ER"),
            optimizerCustomProfileService.findAll(account.id!!, "Tartaglia")
                .mapTo(mutableSetOf()) { it.name },
        )

        mockMvc.perform(
            get("/inventory/artifact-optimizer")
                .param("character", "Tartaglia")
                .param("profile", "custom-${customProfile.id}")
                .session(session),
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Tartaglia DPS")))
            .andExpect(
                content().string(
                    matchesPattern(
                        "(?s).*<option value=\"hp_\"\\s+selected=\"selected\">HP %</option>.*",
                    ),
                ),
            )
            .andExpect(
                content().string(
                    matchesPattern(
                        "(?s).*<option value=\"heartofdepth\"\\s+selected=\"selected\">"
                            + "Heart of Depth · \\d+</option>.*",
                    ),
                ),
            )

        mockMvc.perform(
            get("/inventory/artifact-optimizer")
                .param("character", "Furina")
                .param("profile", "hp")
                .param("lang", "de")
                .session(session),
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Artefakt-Optimierer")))
            .andExpect(content().string(containsString("Wie schwer ist die nächste Verbesserung?")))

        mockMvc.perform(get("/inventory/missing").session(session))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/materials"))

        mockMvc.perform(get("/materials").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Overall planning")))
            .andExpect(content().string(containsString("Materials by source")))
            .andExpect(content().string(containsString("Forsaken Rift")))
            .andExpect(content().string(containsString("Every domain and book family")))
            .andExpect(content().string(containsString("Grouped by source")))
            .andExpect(content().string(containsString("data-snapshot-revision=")))
            .andExpect(content().string(containsString("/materials/api/revision")))
            .andExpect(content().string(containsString("/materials/api/state")))
            .andExpect(content().string(containsString("applyLiveState")))
            .andExpect(content().string(containsString("new DOMParser()")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("fetch(window.location.href"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("window.location.reload()"))))

        mockMvc.perform(get("/materials/api/revision").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("\"revision\":")))

        mockMvc.perform(get("/materials/api/state").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("\"materials\":")))

        mockMvc.perform(get("/admin/images").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Image Sources")))

        mockMvc.perform(
            post("/characters/albedo")
                .session(session)
                .with(csrf())
                .param("owned", "true")
                .param("level", "70")
                .param("ascension", "5")
                .param("constellation", "2")
                .param("normalTalent", "6")
                .param("skillTalent", "8")
                .param("burstTalent", "7")
                .param("targetLevel", "90")
                .param("targetAscension", "6")
                .param("targetNormalTalent", "10")
                .param("targetSkillTalent", "9")
                .param("targetBurstTalent", "8"),
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/characters/albedo"))

        mockMvc.perform(get("/characters/albedo").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("value=\"70\"")))
            .andExpect(content().string(containsString("value=\"90\"")))
            .andExpect(content().string(containsString("class=\"hero-orbit orbit-one\"")))
            .andExpect(content().string(containsString("fetchpriority=\"high\"")))
            .andExpect(
                content().string(
                    containsString("/inventory/artifact-optimizer?character=albedo"),
                ),
            )
            .andExpect(content().string(containsString("Status and progress for Albedo were saved.")))

        mockMvc.perform(get("/characters/tartaglia").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Current build stats")))
            .andExpect(content().string(containsString("main stats and substats")))
            .andExpect(content().string(containsString("Edit additional stat values")))
            .andExpect(content().string(containsString("name=\"statBonusValues\"")))

        mockMvc.perform(
            post("/characters/tartaglia/stats")
                .session(session)
                .with(csrf())
                .param("statBonusKeys", "critRate_", "critDMG_", "enerRech_", "eleMas")
                .param("statBonusValues", "15", "20", "-10", "120"),
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/characters/tartaglia"))

        val tartagliaStats = requireNotNull(targetService.find(account.id!!, "tartaglia"))
        assertEquals(15.0, tartagliaStats.additionalStats["critRate_"])
        assertEquals(20.0, tartagliaStats.additionalStats["critDMG_"])
        assertEquals(-10.0, tartagliaStats.additionalStats["enerRech_"])
        assertEquals(120.0, tartagliaStats.additionalStats["eleMas"])

        mockMvc.perform(get("/characters/tartaglia").session(session))
            .andExpect(status().isOk)
            .andExpect(
                content().string(
                    containsString("Additional stat values for Tartaglia were saved."),
                ),
            )

        mockMvc.perform(
            get("/inventory/artifact-optimizer")
                .param("character", "Tartaglia")
                .session(session),
        )
            .andExpect(status().isOk)
            .andExpect(
                content().string(
                    matchesPattern(
                        "(?s).*name=\"statBonusValues\"[^>]*value=\"15.0\".*",
                    ),
                ),
            )

        val savedTarget = requireNotNull(targetService.find(account.id!!, "albedo"))
        assertEquals(true, savedTarget.owned)
        assertEquals(70, savedTarget.currentLevel)
        assertEquals(5, savedTarget.currentAscension)
        assertEquals(2, savedTarget.currentConstellation)
        assertEquals(6, savedTarget.currentNormalTalent)
        assertEquals(8, savedTarget.currentSkillTalent)
        assertEquals(7, savedTarget.currentBurstTalent)
        assertEquals(90, savedTarget.targetLevel)
        assertEquals(6, savedTarget.targetAscension)
        assertEquals(10, savedTarget.targetNormalTalent)
        assertEquals(9, savedTarget.targetSkillTalent)
        assertEquals(8, savedTarget.targetBurstTalent)

        mockMvc.perform(
            post("/characters/venti")
                .session(session)
                .with(csrf())
                .param("level", "70")
                .param("ascension", "5")
                .param("constellation", "4")
                .param("normalTalent", "6")
                .param("skillTalent", "8")
                .param("burstTalent", "7")
                .param("targetLevel", "80")
                .param("targetAscension", "6")
                .param("targetNormalTalent", "9")
                .param("targetSkillTalent", "9")
                .param("targetBurstTalent", "9"),
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/characters/venti"))

        val unownedCharacter = requireNotNull(targetService.find(account.id!!, "venti"))
        assertEquals(false, unownedCharacter.owned)
        assertEquals(1, unownedCharacter.currentLevel)
        assertEquals(80, unownedCharacter.targetLevel)

        mockMvc.perform(get("/characters").param("query", "Albedo").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Owned")))

        mockMvc.perform(get("/characters").param("query", "Venti").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Not owned")))

        mockMvc.perform(
            get("/characters")
                .param("query", "Albedo")
                .param("lang", "de")
                .session(session),
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Im Besitz")))
            .andExpect(content().string(containsString("Charakterdatenbank")))

        mockMvc.perform(get("/").session(session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Rust")))
            .andExpect(content().string(containsString("Level 20 → 90")))

        mockMvc.perform(
            post("/goals")
                .session(session)
                .with(csrf()),
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/"))
        assertTrue(dashboardGoalService.findAll(account.id!!).isEmpty())

        val secondAccount = userService.register(
            "Second Traveler",
            "second@example.com",
            "another-long-password",
        )
        assertNull(targetService.find(secondAccount.id!!, "albedo"))
        assertTrue(dashboardGoalService.findAll(secondAccount.id!!).isEmpty())
        assertNotNull(optimizerSharingService.find(shareToken))

        val secondLogin = mockMvc.perform(
            post("/login")
                .with(csrf())
                .param("email", "second@example.com")
                .param("password", "another-long-password"),
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(authenticated().withUsername("second@example.com"))
            .andReturn()
        val secondSession = secondLogin.request.getSession(false) as MockHttpSession
        mockMvc.perform(
            multipart("/inventory/upload")
                .file(
                    MockMultipartFile(
                        "file",
                        "genshin-export.json",
                        "application/json",
                        Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT),
                    ),
                )
                .session(secondSession)
                .with(csrf()),
        )
            .andExpect(status().is3xxRedirection)

        mockMvc.perform(get(sharedOptimizerLocation).session(secondSession))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Shared configuration preview")))
            .andExpect(
                content().string(
                    matchesPattern(
                        "(?s).*<option value=\"hp_\"\\s+selected=\"selected\">HP %</option>.*",
                    ),
                ),
            )
    }
}
