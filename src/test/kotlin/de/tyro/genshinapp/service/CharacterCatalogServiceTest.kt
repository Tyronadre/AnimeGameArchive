package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.CharacterProgress
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerWeapon
import java.nio.file.Files
import java.time.Duration
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CharacterCatalogServiceTest {
    private val objectMapper = jacksonObjectMapper()
    private val properties = GenshinContentProperties().also {
        it.cacheDirectory = Files.createTempDirectory("genshin-catalog-test").toString()
        it.characterApiUrl = "http://127.0.0.1:1"
        it.connectTimeout = Duration.ofMillis(100)
        it.requestTimeout = Duration.ofMillis(100)
    }
    private val imageUrlRegistry = ImageUrlRegistry(objectMapper, properties)
    private val fandomImageUrlResolver = FandomImageUrlResolver(properties)
    private val catalog = CharacterCatalogService(
        objectMapper,
        DynamicContentLoader(
            objectMapper,
            properties,
            imageUrlRegistry,
            fandomImageUrlResolver,
        ),
        fandomImageUrlResolver,
    )

    @Test
    fun `loads the local character catalog`() {
        val furina = assertNotNull(catalog.findCharacter("furina"))

        assertTrue(catalog.getCharacters().size >= 80)
        assertEquals("Furina", furina.name)
        assertEquals("Hydro", furina.element)
        assertTrue(furina.ascensionCosts.isNotEmpty())
        assertTrue(furina.talentCosts.isNotEmpty())
    }

    @Test
    fun `calculates level and talent materials from the loaded data`() {
        val furina = assertNotNull(catalog.findCharacter("furina"))
        val materials = MaterialCalculator(catalog).calculate(furina, CharacterProgress())

        assertEquals(168L, materials.first { it.name == "Lakelight Lily" }.amount)
        assertEquals(66L, materials.first { it.name == "Philosophies of Justice" }.amount)
        assertTrue(materials.none { it.name == "Crown of Insight" })
        assertTrue(materials.first { it.name == "Mora" }.amount > 1_000_000)
        assertTrue(materials.any { it.name == "Character EXP" })
    }

    @Test
    fun `calculates all supplied GOOD characters against shared inventory`() {
        val snapshot = GoodImportService(objectMapper).parse(
            Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT),
        )
        val plan = PlayerPlanningService(
            catalog,
            MaterialCalculator(catalog),
            MaterialCraftingService(catalog),
        ).createPlan(snapshot)
        val smallLampGrass = plan.aggregateMaterials.first { it.name == "Small Lamp Grass" }
        val characterExperience = plan.aggregateMaterials.first { it.name == "Character EXP" }
        val smallLampGrassNeeds = plan.characterNeeds(smallLampGrass.id)

        assertEquals(77, plan.characters.size)
        assertEquals(
            setOf("Jahoda", "Durin", "Columbina", "Illuga"),
            plan.unmatchedCharacterKeys.toSet(),
        )
        assertEquals(29L, smallLampGrass.owned)
        assertEquals(
            (smallLampGrass.required - 29L).coerceAtLeast(0L),
            smallLampGrass.missing,
        )
        assertEquals(smallLampGrass.required, smallLampGrassNeeds.sumOf { it.required })
        assertTrue(smallLampGrassNeeds.any { it.character.name == "Amber" })
        assertEquals(2_105_000L, characterExperience.owned)
    }

    @Test
    fun `resolves ascension weapon and manual crit rate before artifacts`() {
        val weaponDataService = mock(WeaponDataService::class.java)
        `when`(weaponDataService.find("SkywardHarp")).thenReturn(
            WeaponDefinition(
                key = "skywardharp",
                name = "Skyward Harp",
                rarity = 5,
                secondaryStatType = "FIGHT_PROP_CRITICAL",
                baseSecondaryStat = 4.8,
                ascensionCosts = emptyMap(),
            ),
        )
        val service = OptimizerCombatStatService(catalog, weaponDataService)
        val character = PlayerCharacterState(
            key = "Furina",
            level = 90,
            constellation = 0,
            ascension = 6,
            normalTalent = 1,
            skillTalent = 1,
            burstTalent = 1,
        )
        val weapon = PlayerWeapon(
            key = "SkywardHarp",
            level = 90,
            ascension = 6,
            refinement = 1,
            location = "Furina",
            locked = true,
        )

        val stats = service.resolve(character, weapon, additionalCritRate = 7.5)

        assertEquals(24.2, stats.characterStats.getValue("critRate_"), 0.01)
        assertEquals(22.0512, stats.weaponStats.getValue("critRate_"), 0.01)
        assertEquals(7.5, stats.bonusStats.getValue("critRate_"), 0.01)
        assertEquals(53.7512, stats.totals.getValue("critRate_"), 0.01)
        assertEquals(50.0, stats.totals.getValue("critDMG_"), 0.01)
        assertEquals(100.0, stats.totals.getValue("enerRech_"), 0.01)
    }
}
