package de.tyro.genshinapp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.DesktopUserProvider
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.entity.User
import de.tyro.genshinapp.model.PlayerArtifactStat
import de.tyro.genshinapp.repository.PlayerArtifactInstanceRepository
import de.tyro.genshinapp.service.player.PlayerArtifactStorageService
import de.tyro.genshinapp.service.player.GoodImportService
import de.tyro.genshinapp.desktop.irminsul.IrminsulCaptureState
import de.tyro.genshinapp.desktop.irminsul.IrminsulIntegrationService
import de.tyro.genshinapp.desktop.irminsul.IrminsulStatusEvent
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.FightProperty
import de.tyro.genshinapp.model.WeaponImageType
import de.tyro.genshinapp.repository.UserRepository
import de.tyro.genshinapp.repository.GameWeaponRepository
import de.tyro.genshinapp.repository.PlayerWeaponInstanceRepository
import de.tyro.genshinapp.service.player.GoodImportServiceTest
import de.tyro.genshinapp.service.content.ImageUrlRegistry
import de.tyro.genshinapp.service.player.PlayerSnapshotStore
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        "spring.messages.fallback-to-system-locale=false",
        "genshin.content.cache-directory=build/test-desktop-cache-\${random.uuid}",
        "genshin.content.hoyolab-wiki-enabled=false",
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
    private val artifactRepository: PlayerArtifactInstanceRepository,
    private val artifactStorage: PlayerArtifactStorageService,
    private val buildStatRepository: de.tyro.genshinapp.repository.BuildStatTableRepository,
    private val characterTargets: de.tyro.genshinapp.service.planning.CharacterTargetService,
    private val characterCatalogStore: de.tyro.genshinapp.service.catalog.CharacterCatalogStore,
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
    fun `Nefer damage calculator renders every talent group and live inputs`() {
        mockMvc.perform(get("/damage-calculator/amber"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("data-damage-calculator")))
            .andExpect(content().string(containsString("data-damage-input=\"attack\"")))
            .andExpect(content().string(containsString("data-damage-input=\"neferConstellation\"")))
            .andExpect(content().string(containsString("data-damage-instance=\"normal-1\"")))
            .andExpect(content().string(containsString("data-damage-instance=\"phantasm-shades-3\"")))
            .andExpect(content().string(containsString("data-damage-instance=\"burst-2\"")))
            .andExpect(content().string(containsString("data-pipeline-defense")))
            .andExpect(content().string(containsString("data-formula=\"base\"")))
            .andExpect(content().string(containsString("data-damage-element=\"dendro\"")))
            .andExpect(content().string(containsString("data-formula-meta=\"attack\"")))
            .andExpect(content().string(containsString("data-formula-tooltip=")))
            .andExpect(content().string(containsString("data-damage-effect=\"aWagerOfMoonlight\"")))
            .andExpect(content().string(containsString("data-damage-effect=\"reliquaryOfTruth\"")))
            .andExpect(content().string(containsString("data-effect-toggle-label=\"aWagerOfMoonlight\"")))
            .andExpect(content().string(containsString("data-team-slot=\"1\"")))
            .andExpect(content().string(containsString("data-damage-effect=\"laumaPaleHymn\"")))
            .andExpect(content().string(containsString("data-damage-effect=\"columbinaLunarDomain\"")))
            .andExpect(content().string(containsString("data-damage-effect=\"neferC1\"")))
            .andExpect(content().string(containsString("data-effect-enable-all=\"character\"")))
            .andExpect(content().string(containsString("data-effect-disable-all=\"team\"")))
            .andExpect(content().string(containsString("data-effect-reset-ownership")))
            .andExpect(content().string(containsString("data-damage-instance=\"phantasm-c6-extra\"")))
            .andExpect(content().string(containsString("data-damage-model=\"lunarReaction\"")))
            .andExpect(content().string(containsString("data-expand-calculations")))
            .andExpect(content().string(containsString("Veils of Falsehood")))
            .andExpect(content().string(containsString("/js/damage-calculator.js")))
            .andExpect(content().string(containsString("/damage-calculator-v2/nefer")))
    }

    @Test
    fun `hand authored V2 calculator renders as a separate endpoint driven page`() {
        mockMvc.perform(get("/damage-calculator-v2/nefer"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("data-damage-calculator-v2")))
            .andExpect(content().string(containsString("data-artifact-comparison")))
            .andExpect(content().string(containsString("data-comparison-url=\"/api/damage-formulas/v2/nefer/artifact-comparisons\"")))
            .andExpect(content().string(containsString("damage-element-dendro")))
            .andExpect(content().string(containsString("data-profile-url=\"/api/damage-formulas/v2/nefer\"")))
            .andExpect(content().string(containsString("data-calculate-url=\"/api/damage-formulas/v2/nefer/calculate\"")))
            .andExpect(content().string(containsString("data-team-url=\"/api/damage-formulas/v2/nefer/team\"")))
            .andExpect(content().string(containsString("data-v2-team")))
            .andExpect(content().string(containsString("data-v2-stored-team")))
            .andExpect(content().string(containsString("data-v2-stored-team-toggle")))
            .andExpect(content().string(containsString("data-v2-stored-team-grid")))
            .andExpect(content().string(containsString("data-label-team-search")))
            .andExpect(content().string(containsString("data-v2-tab=\"effects\"")))
            .andExpect(content().string(containsString("data-v2-tab=\"stats\"")))
            .andExpect(content().string(containsString("data-v2-tab=\"damage\"")))
            .andExpect(content().string(containsString("aria-orientation=\"vertical\"")))
            .andExpect(content().string(containsString("data-v2-character-stats")))
            .andExpect(content().string(containsString("data-v2-calculation-inputs")))
            .andExpect(content().string(containsString("data-label-reset=\"Reset\"")))
            .andExpect(content().string(containsString("data-label-entered=\"Base\"")))
            .andExpect(content().string(containsString("data-label-section-team-stats=\"Effect conditions\"")))
            .andExpect(content().string(containsString("data-label-stat-calculation=\"Calculation\"")))
            .andExpect(content().string(containsString("data-label-skill-description=\"Skill description\"")))
            .andExpect(content().string(containsString("data-v2-reaction-toggle")))
            .andExpect(content().string(containsString("data-label-reaction-multiplier=\"Reaction multiplier\"")))
            .andExpect(content().string(containsString("data-label-reaction-transformative=\"Transformative\"")))
            .andExpect(content().string(containsString("all standard elemental reactions")))
            .andExpect(content().string(containsString("data-v2-talent-description-source")))
            .andExpect(content().string(containsString("data-talent-key=\"combat2\"")))
            .andExpect(content().string(containsString("A dance that dissolved dynasties")))
            .andExpect(content().string(not(containsString("data-label-character-buff-inputs"))))
            .andExpect(content().string(not(containsString("damage-v2-pipeline"))))
            .andExpect(content().string(containsString("Team starting stats")))
            .andExpect(content().string(containsString("/damage-calculator/nefer")))
            .andExpect(content().string(containsString("/css/damage-calculator-v2.css")))
            .andExpect(content().string(containsString("/js/damage-calculator-v2.js")))
            .andExpect(content().string(not(containsString("id=\"damage-profile\""))))
    }

    @Test
    fun `V2 artifact analysis uses the imported team and can be cancelled without changing equipment`() {
        val userId = requireNotNull(assertNotNull(userRepository.findByEmailIgnoreCase(DesktopUserProvider.DESKTOP_EMAIL)).id)
        val snapshot = snapshotStore.save(userId, Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT))
        val mapper = jacksonObjectMapper()
        val document = mapper.readTree(mockMvc.perform(get("/api/damage-formulas/v2/nefer"))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        val goals = listOf("shades-hit-1", "shades-hit-2", "shades-hit-3")
        val request = mapper.writeValueAsString(mapOf(
            "calculation" to mapOf("teamKeys" to listOf("nefer", "lauma", "nahida", "columbina")),
            "formulaIds" to goals, "contextFingerprint" to document.path("contextFingerprint").asText(),
        ))
        val started = mapper.readTree(mockMvc.perform(post("/api/damage-formulas/v2/nefer/artifact-comparisons")
            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isAccepted).andReturn().response.contentAsString)
        val url = "/api/damage-formulas/v2/nefer/artifact-comparisons/" + started.path("runId").asText()
        var result = started
        val deadline = System.nanoTime() + 30_000_000_000L
        try {
            while (result.path("baselineDamage").isNull && System.nanoTime() < deadline) {
                Thread.sleep(20)
                result = mapper.readTree(mockMvc.perform(get(url)).andExpect(status().isOk).andReturn().response.contentAsString)
            }
            val expected = document.path("defaultCalculation").path("results").filter { it.path("formulaId").asText() in goals }
                .sumOf { it.path("average").asDouble() }
            assertFalse(result.path("baselineDamage").isNull, result.toString())
            assertEquals(expected, result.path("baselineDamage").asDouble(), .000001)
        } finally {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url).with(csrf()))
                .andExpect(status().isOk)
        }
        assertEquals(snapshot.artifacts, assertNotNull(snapshotStore.current(userId)).artifacts)
    }

    @Test
    fun `hand authored V2 calculator uses the active character element theme`() {
        mockMvc.perform(get("/damage-calculator-v2/arlecchino"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("damage-element-pyro")))
            .andExpect(content().string(not(containsString("damage-element-dendro"))))
    }

    @Test
    fun `character and weapon definitions have simple JSON endpoints`() {
        val mapper = jacksonObjectMapper()


        val character = mapper.readTree(mockMvc.perform(get("/api/characters/nefer"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn().response.contentAsString)
        assertEquals("nefer", character.path("key").asText())
        assertEquals("Dendro", character.path("element").asText())
        assertTrue(character.path("talents").isArray)
        assertTrue(character.path("baseStats").isObject)

        val weapon = mapper.readTree(mockMvc.perform(get("/api/weapons/rust"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn().response.contentAsString)
        assertEquals("rust", weapon.path("key").asText())
        assertTrue(weapon.path("rarity").asInt() > 0)
        assertTrue(weapon.has("baseAttack"))

        mockMvc.perform(get("/api/characters/not-a-character")).andExpect(status().isNotFound)
        mockMvc.perform(get("/api/weapons/not-a-weapon")).andExpect(status().isNotFound)
    }

    @Test
    fun `stored V2 team builds use database equipment and ignore all calculator edits`() {
        mockMvc.perform(get("/damage-calculator-v2/nefer")).andExpect(status().isOk)
        val userId = requireNotNull(userRepository.findByEmailIgnoreCase(DesktopUserProvider.DESKTOP_EMAIL)!!.id)
        val snapshot = snapshotStore.save(userId, Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT))
        val mapper = jacksonObjectMapper()
        fun document() = mapper.readTree(mockMvc.perform(get("/api/damage-formulas/v2/nefer"))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        val before = document().path("teamBuilds")
        assertEquals(4, before.size())
        before.forEach { assertTrue(it.path("issues").isEmpty, "${it.path("key")}: ${it.path("issues")}") }
        assertEquals(setOf("nefer", "lauma", "nahida", "columbina"), before.map { it.path("key").asText() }.toSet())
        val nefer = before.first { it.path("key").asText() == "nefer" }
        assertTrue(nefer.path("issues").isEmpty, nefer.path("issues").toString())
        assertEquals(90, nefer.path("level").asInt())
        assertEquals(6, nefer.path("ascension").asInt())
        assertEquals(1, nefer.path("constellation").asInt())
        assertEquals(5, nefer.path("artifacts").size())
        assertEquals("reliquaryoftruth", GoodKeyNormalizer.normalize(nefer.path("weapon").path("key").asText()))
        assertEquals(344.0, nefer.path("characterStats").first { it.path("key").asText() == "atk" }.path("value").asDouble(), 1e-7)
        assertEquals(541.829758, nefer.path("weapon").path("stats").first { it.path("key").asText() == "atk" }.path("value").asDouble(), 1e-7)
        assertEquals(0.0, nefer.path("totalStats").first { it.path("key").asText() == "dendro_dmg_" }.path("value").asDouble())
        assertFalse(nefer.has("sources"))
        assertFalse(nefer.has("importedAt"))
        assertEquals(0L, buildStatRepository.count(), "Installation does not create legacy JSON cache rows")
        val metadata = assertNotNull(characterCatalogStore.findCharacter("nefer"))
        assertEquals(27.0, metadata.stats?.baseStats?.get(FightProperty.FIGHT_PROP_BASE_ATTACK))
        assertEquals(989.0, metadata.stats?.baseStats?.get(FightProperty.FIGHT_PROP_BASE_HP))

        val changed = mapper.readTree(mockMvc.perform(post("/api/damage-formulas/v2/nefer/team").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("""{
                "teamKeys":["nefer","lauma","nahida","columbina"],
                "inputs":{"attack":9999,"elementalMastery":3000,"characterLevel":1,"neferConstellation":6},
                "enabledEffects":{"aWagerOfMoonlight":false,"character.lauma.laumaPaleHymn":false}
            }"""))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertEquals(before, changed.path("teamBuilds"))

        val previousManual = characterTargets.find(userId, "nefer")?.additionalStats.orEmpty()
        try {
            characterTargets.saveAdditionalStats(userId, "nefer", mapOf("atk" to 9999.0, "eleMas" to 9999.0))
            assertEquals(before, document().path("teamBuilds"))
        } finally {
            characterTargets.saveAdditionalStats(userId, "nefer", previousManual)
        }

        // A real database equipment edit does update the baseline, independently of cached GOOD JSON.
        val flower = snapshot.artifacts.first { it.location == "Nefer" && it.slotKey == "flower" }
        val stored = artifactRepository.findAllByUser_IdOrderByImportPositionAsc(userId)
            .first { it.location == "Nefer" && it.slotKey == "flower" }
        try {
            stored.level = 0
            artifactRepository.save(stored)
            val refreshed = document().path("teamBuilds").first { it.path("key").asText() == "nefer" }
            assertEquals(717.0, refreshed.path("artifacts").first { it.path("slot").asText() == "flower" }
                .path("mainStat").path("value").asDouble())
            val oldHp = nefer.path("totalStats").first { it.path("key").asText() == "hp" }.path("value").asDouble()
            val newHp = refreshed.path("totalStats").first { it.path("key").asText() == "hp" }.path("value").asDouble()
            assertEquals(4780.0 - 717.0, oldHp - newHp, 1e-7)
        } finally {
            stored.level = flower.level
            artifactRepository.save(stored)
        }
        val solo = mapper.readTree(mockMvc.perform(get("/api/damage-formulas/v2/nefer").param("team", "nefer"))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertEquals(listOf("nefer"), solo.path("teamBuilds").map { it.path("key").asText() })
    }

    @Test
    fun `Nefer damage calculator starts from imported character values`() {
        mockMvc.perform(get("/damage-calculator/nefer"))
            .andExpect(status().isOk)
        val user = assertNotNull(
            userRepository.findByEmailIgnoreCase(DesktopUserProvider.DESKTOP_EMAIL),
        )
        snapshotStore.save(
            requireNotNull(user.id),
            Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT),
        )

        val response = mockMvc.perform(get("/damage-calculator/nefer"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("data-current-loaded=\"true\"")))
            .andExpect(content().string(containsString("class=\"damage-current-build-note\"")))
            .andExpect(content().string(containsString("\"normalTalentLevel\"")))
            .andExpect(content().string(containsString("\"defaultValue\":9.0")))
            .andExpect(
                content().string(
                    containsString(
                        "\"id\":\"neferConstellation\",\"messageKey\":" +
                            "\"damage.input.neferConstellation\",\"formulaLabel\":\"Nefer C\"," +
                            "\"type\":\"range\",\"min\":0.0,\"max\":6.0,\"step\":1.0," +
                            "\"defaultValue\":1.0",
                    ),
                ),
            )
            .andReturn()
            .response
            .contentAsString

        fun loadedInputValue(inputId: String): Double {
            val input = assertNotNull(
                Regex("""<input[^>]*data-damage-input="$inputId"[^>]*>""").find(response),
                "Missing damage input $inputId",
            ).value
            assertTrue(input.contains("data-current-loaded=\"true\""))
            return assertNotNull(
                Regex("""value="([^"]+)""").find(input),
                "Missing value for damage input $inputId",
            ).groupValues[1].toDouble()
        }

        assertEquals(1333.583, loadedInputValue("attack"), 0.001)
        assertEquals(0.0, loadedInputValue("elementalDamageBonus"), 0.001)

        val v2Document = jacksonObjectMapper().readTree(
            mockMvc.perform(get("/api/damage-formulas/v2/nefer"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString,
        )
        val v2Attack = v2Document.path("profile").path("inputs")
            .first { input -> input.path("id").asText() == "attack" }
        assertEquals(1333.583, v2Attack.path("defaultValue").asDouble(), 0.001)
        assertTrue(v2Attack.path("currentValueLoaded").asBoolean())
        val v2Constellation = v2Document.path("profile").path("inputs")
            .first { input -> input.path("id").asText() == "neferConstellation" }
        assertEquals(1.0, v2Constellation.path("defaultValue").asDouble(), 0.001)
        assertTrue(v2Constellation.path("currentValueLoaded").asBoolean())
        val v2Effects = v2Document.path("profile").path("effects")
        assertTrue(v2Effects.first { it.path("id").asText() == "neferC1" }.path("defaultEnabled").asBoolean())
        assertFalse(v2Effects.first { it.path("id").asText() == "neferC2" }.path("defaultEnabled").asBoolean())
        listOf("character.lauma.laumaElementalMastery", "character.nahida.nahidaElementalMastery", "character.columbina.columbinaElementalMastery",
            "character.lauma.laumaConstellation", "character.nahida.nahidaConstellation", "character.columbina.columbinaConstellation", "character.columbina.columbinaBurstTalentLevel")
            .forEach { id ->
                assertTrue(v2Document.path("profile").path("inputs").first { it.path("id").asText() == id }
                    .path("currentValueLoaded").asBoolean(), "Expected imported teammate input $id")
            }
        assertTrue(v2Document.path("profile").path("inputs").first { it.path("id").asText() == "character.columbina.columbinaMaxHp" }
            .path("currentValueLoaded").asBoolean())
        val columbinaBaseHp = v2Document.path("profile").path("inputs")
            .first { it.path("id").asText() == "character.columbina.columbinaBaseHp" }
        assertTrue(columbinaBaseHp.path("currentValueLoaded").asBoolean())
        assertEquals("internal", columbinaBaseHp.path("section").asText())
        assertFalse(v2Document.path("profile").path("inputs").any { it.path("id").asText() == "otherDamageBonus" })
        val characterStats = v2Document.path("profile").path("characterStatInputs")
        assertEquals(setOf("nefer", "lauma", "nahida", "columbina"), characterStats.fieldNames().asSequence().toSet())
        characterStats.forEach { stats -> assertEquals(9, stats.size()) }
        val characterProgress = v2Document.path("profile").path("characterProgressInputs")
        assertEquals(setOf("nefer", "lauma", "nahida", "columbina"), characterProgress.fieldNames().asSequence().toSet())
        characterProgress.forEach { progress -> assertEquals(4, progress.size()) }
        val nahidaNormalLevel = characterProgress.path("nahida").path("normalTalentLevel").asText()
        assertTrue(v2Document.path("profile").path("inputs").first { it.path("id").asText() == nahidaNormalLevel }
            .path("currentValueLoaded").asBoolean())
        assertEquals(setOf("character.nahida.nahidaMarkedOpponents", "character.nahida.nahidaQuickenActive"),
            v2Document.path("profile").path("characterBuffInputs").path("nahida").map { it.asText() }.toSet())
        val neferBuild = v2Document.path("teamBuilds").first { it.path("key").asText() == "nefer" }
        val neferHp = neferBuild.path("totalStats").first { it.path("key").asText() == "hp" }.path("value").asDouble()
        val neferHpInput = characterStats.path("nefer").path("maxHp").asText()
        assertEquals(neferHp, v2Document.path("profile").path("inputs")
            .first { it.path("id").asText() == neferHpInput }.path("defaultValue").asDouble(), 0.001)
        assertEquals(
            8,
            v2Document.path("profile").path("talents")
                .first { talent -> talent.path("key").asText() == "skill" }
                .path("formulas")
                .size(),
        )
    }


    @Test
    fun `V2 team endpoint preserves edits and excludes removed support effects`() {
        val mapper = jacksonObjectMapper()
        val payload = """{"teamKeys":["nefer","lauma"],"inputs":{"attack":1777,"character.lauma.laumaConstellation":3},"enabledEffects":{"character.lauma.laumaPaleHymn":false}}"""
        val document = mapper.readTree(mockMvc.perform(post("/api/damage-formulas/v2/nefer/team")
            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertEquals(setOf("nefer", "lauma"), document.path("profile").path("selectedTeam").map { it.asText() }.toSet())
        assertEquals(1777.0, document.path("defaultCalculation").path("inputs").path("attack").asDouble())
        assertFalse(document.path("profile").path("effects").any { it.path("id").asText().startsWith("nahida") })
        val active = document.path("defaultCalculation").path("activeEffectIds").map { it.asText() }
        assertFalse("character.lauma.laumaPaleHymn" in active)
        assertTrue("character.lauma.laumaC3" in active)
        val solo = mapper.readTree(mockMvc.perform(post("/api/damage-formulas/v2/nefer/calculate")
            .with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"teamKeys":["nefer"],"enabledEffects":{"character.lauma.laumaPaleHymn":true}}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertFalse(solo.path("activeEffectIds").any { it.asText() == "character.lauma.laumaPaleHymn" })
        mockMvc.perform(post("/api/damage-formulas/v2/nefer/team").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("""{"teamKeys":["missing"]}"""))
            .andExpect(status().isBadRequest)
        val soloProfile = mapper.readTree(mockMvc.perform(get("/api/damage-formulas/v2/nefer").param("team", "nefer"))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertEquals(listOf("nefer"), soloProfile.path("profile").path("selectedTeam").map { it.asText() })
        val roster = soloProfile.path("profile").path("teamMembers")
        assertTrue(roster.size() > 100)
        assertEquals(roster.size(), roster.map { it.path("key").asText() }.distinct().size)
        assertTrue(roster.first { it.path("key").asText() == "amber" }.path("iconUrl").asText().isNotBlank())
        val arbitrary = mapper.readTree(mockMvc.perform(post("/api/damage-formulas/v2/nefer/team")
            .with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"teamKeys":["nefer","amber"]}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertEquals(setOf("nefer", "amber"), arbitrary.path("profile").path("selectedTeam").map { it.asText() }.toSet())
        assertEquals(setOf("nefer", "amber"), arbitrary.path("teamBuilds").map { it.path("key").asText() }.toSet())
        assertEquals(6, arbitrary.path("profile").path("supportProfiles").first { it.path("key").asText() == "amber" }
            .path("effects").count { it.path("category").asText() == "constellation" })
    }

    @Test
    fun `artifact migration preserves legacy edits and database equipment survives reloads`(@TempDir directory: Path) {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val properties = GenshinContentProperties().apply { cacheDirectory = directory.toString() }
        val userId = requireNotNull(userRepository.save(User().apply {
            name = "Artifact migration"; email = "artifact-migration@example.test"; passwordHash = "test"
        }).id)
        val otherId = requireNotNull(userRepository.save(User().apply {
            name = "Other inventory"; email = "artifact-isolation@example.test"; passwordHash = "test"
        }).id)
        val bytes = """{"format":"GOOD","version":3,"characters":[],"artifacts":[
            {"setKey":"DeepwoodMemories","slotKey":"flower","level":20,"rarity":5,"mainStatKey":"hp","location":"Nahida","lock":true,"substats":[{"key":"eleMas","value":40}],"totalRolls":9,"astralMark":true,"elixirCrafted":true},
            {"setKey":"NightOfTheSkysUnveiling","slotKey":"plume","level":20,"rarity":5,"mainStatKey":"atk","location":"Nefer","lock":false,"substats":[]}
        ],"weapons":[],"materials":{}}""".toByteArray()
        fun store(database: Boolean) = PlayerSnapshotStore(properties, GoodImportService(mapper), mapper,
            playerArtifactStorageService = if (database) artifactStorage else null)
        val legacy = store(false)
        legacy.save(userId, bytes)
        val legacyEdited = legacy.updateArtifacts(userId) { list -> list.mapIndexed { index, artifact ->
            if (index == 0) artifact.copy(location = "Lauma", substats = listOf(PlayerArtifactStat("eleMas", 70.0))) else artifact
        } }
        val migrated = store(true)
        assertEquals(legacyEdited.artifacts, assertNotNull(migrated.current(userId)).artifacts)
        val originalIds = artifactRepository.findAllByUser_IdOrderByImportPositionAsc(userId).map { it.id }
        assertEquals(2, originalIds.size)
        assertTrue(artifactStorage.findAll(otherId).isEmpty())
        val changed = migrated.updateArtifacts(userId) { list -> list.map { it.copy(location = null, locked = false) } }
        assertEquals(originalIds, artifactRepository.findAllByUser_IdOrderByImportPositionAsc(userId).map { it.id })
        assertEquals(changed.artifacts, assertNotNull(store(true).current(userId)).artifacts)
        assertTrue(Files.isRegularFile(migrated.filePath(userId).resolveSibling("artifact-overrides.json")))
        migrated.updateArtifacts(userId) { emptyList() }
        assertTrue(assertNotNull(store(true).current(userId)).artifacts.isEmpty())
        assertTrue(artifactRepository.findAllByUser_IdOrderByImportPositionAsc(userId).isEmpty())
        artifactStorage.initializeIfMissing(userId, legacyEdited.artifacts)
        assertTrue(artifactStorage.findAll(userId).isEmpty(), "An intentionally empty inventory must not remigrate old files")
        val imported = migrated.save(userId, bytes)
        assertEquals(imported.artifacts, artifactStorage.findAll(userId))
        assertEquals(imported.artifacts, assertNotNull(store(true).current(userId)).artifacts)
        assertTrue(artifactStorage.findAll(otherId).isEmpty())
    }

    @Test
    fun `V2 derives artifact categories from database and set toggles remove their actual stat contribution`() {
        val userId = requireNotNull(assertNotNull(userRepository.findByEmailIgnoreCase(DesktopUserProvider.DESKTOP_EMAIL)).id)
        val snapshot = snapshotStore.save(userId, Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT))
        assertEquals(snapshot.artifacts, artifactStorage.findAll(userId))
        assertEquals(snapshot.artifacts.size, artifactRepository.findAllByUser_IdOrderByImportPositionAsc(userId).size)
        val mapper = jacksonObjectMapper()
        val document = mapper.readTree(mockMvc.perform(get("/api/damage-formulas/v2/nefer"))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        val profile = document.path("profile")
        val loadouts = profile.path("artifactLoadouts")
        assertEquals(4, loadouts.size())
        assertEquals(4, loadouts.first { it.path("characterKey").asText() == "nefer" }.path("sets")
            .first { it.path("key").asText() == "nightoftheskysunveiling" }.path("pieces").asInt())
        val effects = profile.path("effects")
        val artifactEffects = effects.filter { it.path("artifactOrigin").isObject }
        assertTrue(artifactEffects.any { it.path("category").asText() == "artifact:columbina" })
        val defaults = profile.path("inputs").associate { it.path("id").asText() to it.path("defaultValue").asDouble() }
        val enabled = effects.associate { it.path("id").asText() to false }.toMutableMap()
        fun calculate() = mapper.readTree(mockMvc.perform(post("/api/damage-formulas/v2/nefer/calculate")
            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapOf(
                "teamKeys" to listOf("nefer", "lauma", "nahida", "columbina"), "enabledEffects" to enabled,
            )))).andExpect(status().isOk).andReturn().response.contentAsString)
        val off = calculate()
        assertEquals(defaults.getValue("elementalMastery"), off.path("effectiveStats").path("elementalMastery").asDouble())
        assertEquals(defaults.getValue("critRate"), off.path("effectiveStats").path("critRate").asDouble())
        artifactEffects.forEach { enabled[it.path("id").asText()] = true }
        val on = calculate()
        assertEquals(200.0, on.path("effectiveStats").path("elementalMastery").asDouble() - defaults.getValue("elementalMastery"), .001)
        assertEquals(30.0, on.path("effectiveStats").path("critRate").asDouble() - defaults.getValue("critRate"), .001)
        val solo = mapper.readTree(mockMvc.perform(get("/api/damage-formulas/v2/nefer").param("team", "nefer"))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertEquals(1, solo.path("profile").path("artifactLoadouts").size())
        assertFalse(solo.path("profile").path("effects").any { it.path("category").asText() == "artifact:columbina" })
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
