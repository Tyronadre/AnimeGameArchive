package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.controller.DamageFormulaEditorV2Controller
import de.tyro.genshinapp.controller.FormulaEditorRequestV2
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FormulaEditorV2Test {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private lateinit var editor: DamageFormulaEditorV2Controller
    @BeforeEach fun setup() {
        val source = Path.of("src/main/resources/data/damage-formulas-v2")
        Files.walk(source).use { paths -> paths.forEach { path ->
            val target = directory.resolve(source.relativize(path))
            if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
        } }
        editor = DamageFormulaEditorV2Controller(mapper, directory.toString())
    }
    @AfterEach fun cleanup() {
        DamageFormulaResourcesV2.sourceRoot = null
        DamageFormulaResourcesV2.revision.incrementAndGet()
    }
    @Test fun `invalid references are rejected before source files change`() {
        val current = editor.read("nefer.json")
        val broken = current.getValue("content").replace("\"id\": \"veilStacks\"", "\"id\": \"missingInput\"")
        val error = assertFailsWith<IllegalArgumentException> {
            editor.save(FormulaEditorRequestV2("nefer.json", broken, current.getValue("revision")))
        }
        assertContains(error.message.orEmpty(), "veilStacks")
        assertEquals(current, editor.read("nefer.json"))
    }
    @Test fun `save invalidates formula caches and rejects concurrent overwrites`() {
        val formulas = DamageFormulaServiceV2(mapper)
        assertEquals("Nefer", formulas.profile("nefer")?.name)
        val current = editor.read("nefer.json")
        val tree = mapper.readTree(current.getValue("content")) as com.fasterxml.jackson.databind.node.ObjectNode
        tree.put("name", "Edited Nefer")
        val request = FormulaEditorRequestV2("nefer.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree), current.getValue("revision"))
        editor.save(request)
        assertEquals("Edited Nefer", formulas.profile("nefer")?.name)
        assertFailsWith<org.springframework.web.server.ResponseStatusException> { editor.save(request) }
    }
    @Test fun `files outside formula directory cannot be read`() {
        assertFailsWith<IllegalArgumentException> { editor.read("../character-stages.json") }
        assertFailsWith<IllegalArgumentException> { editor.read("README.md") }
    }
    @Test fun `weapon expressions are validated against their local controls`() {
        val current = editor.read("weapons/reliquaryoftruth.json")
        val broken = current.getValue("content").replace("\"id\": \"refinement\"", "\"id\": \"missingRefinement\"")
        assertFailsWith<IllegalArgumentException> {
            editor.validate(FormulaEditorRequestV2("weapons/reliquaryoftruth.json", broken))
        }
    }
    @Test fun `talent picker exposes named Lauma Burst scaling parameters`() {
        val catalog = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(editor.catalog())
        val lauma = catalog.first { it.path("file").asText() == "lauma.json" }
        val burst = lauma.path("talents").first { it.path("key").asText() == "combat3" }
        val scaling = burst.path("parameters").first { it.path("key").asText() == "param4" }
        assertEquals("Lunar-Bloom DMG Increase", scaling.path("label").asText())
        assertEquals(15, scaling.path("values").size())
    }
    @Test fun `editor saves a unified party buff and runtime binds its portable references`() {
        val current = editor.read("lauma.json")
        val document = mapper.readTree(current.getValue("content")) as com.fasterxml.jackson.databind.node.ObjectNode
        val effect = mapper.readTree("""{
            "id":"editorPartyBuff", "name":"Editor party buff", "source":"Lauma", "description":"Test", "category":"team",
            "recipient":"party", "activation":{"event":"buffActive"},
            "formulaModifiers":[{"targets":{"damageKinds":["lunar"]},"base":{"type":"multiply","operands":[
                {"type":"input","input":"source:elementalMastery"},
                {"type":"talentParameter","talent":"combat3","parameter":"param4","levelInput":"local:laumaBurstTalentLevel"}
            ]}}]
        }""")
        (document.get("effects") as com.fasterxml.jackson.databind.node.ArrayNode).add(effect)
        editor.save(FormulaEditorRequestV2("lauma.json", mapper.writeValueAsString(document), current.getValue("revision")))
        val calculator = DamageFormulaServiceV2(mapper)
        val profile = DamageFormulaTeamServiceV2(mapper).compose(assertNotNull(calculator.profile("nefer")), setOf("lauma"))
        val result = calculator.calculate(profile, DamageCalculationRequestV2(
            mapOf("character.lauma.laumaElementalMastery" to 1000.0, "character.lauma.laumaBurstTalentLevel" to 10.0),
            profile.effects.associate { it.id to (it.id == "character.lauma.editorPartyBuff") }))
        assertEquals(4000.32, result.results.first { it.formulaId == "shades-hit-1" }.base.additiveValue, 1e-6)
        assertTrue(result.results.filter { it.damageType == "directDendro" }.all { it.base.additiveValue == 0.0 })
    }

    @Test fun `saving a shared damage type updates all consumers without editing character files`() {
        val formulas = DamageFormulaServiceV2(mapper)
        val characters = listOf("bennett", "arlecchino", "xiangling")
        val originals = characters.associateWith { editor.read("$it.json") }
        characters.forEach { key -> assertTrue(formulas.profile(key)!!.damageTypes.first { it.id == "directPyro" }.allowsCriticalHits) }
        val current = editor.read("damage-types.json")
        val tree = mapper.readTree(current.getValue("content"))
        (tree["damageTypes"].first { it["id"].asText() == "directPyro" } as com.fasterxml.jackson.databind.node.ObjectNode)
            .put("allowsCriticalHits", false)
        editor.save(FormulaEditorRequestV2("damage-types.json", mapper.writeValueAsString(tree), current.getValue("revision")))
        characters.forEach { key ->
            assertFalse(formulas.profile(key)!!.damageTypes.first { it.id == "directPyro" }.allowsCriticalHits)
            assertEquals(originals.getValue(key), editor.read("$key.json"))
            assertFalse(mapper.readTree(originals.getValue(key).getValue("content")).has("damageTypes"))
        }
    }

    @Test fun `used types cannot be removed and unused type expressions are validated`() {
        val current = editor.read("damage-types.json")
        val tree = mapper.readTree(current.getValue("content"))
        val types = tree["damageTypes"] as com.fasterxml.jackson.databind.node.ArrayNode
        types.remove(types.indexOfFirst { it["id"].asText() == "directPyro" })
        assertContains(assertFailsWith<IllegalArgumentException> {
            editor.save(FormulaEditorRequestV2("damage-types.json", mapper.writeValueAsString(tree), current.getValue("revision")))
        }.message.orEmpty(), "directPyro")
        types.add(mapper.readTree("""{"id":"unused","element":"Geo","innateDamageBonus":{"unit":"ratio","expression":{"type":"input","input":"source:missingStat"}}}"""))
        assertContains(assertFailsWith<IllegalArgumentException> {
            editor.validate(FormulaEditorRequestV2("damage-types.json", mapper.writeValueAsString(tree)))
        }.message.orEmpty(), "missingStat")
        assertEquals(current, editor.read("damage-types.json"))
    }

    @Test fun `talents select shared types and mixed elements use their own bonus and resistance`() {
        val current = editor.read("skirk.json")
        val tree = mapper.readTree(current.getValue("content"))
        val hit = tree["talents"].flatMap { it["formulas"] }.first()
        (hit as com.fasterxml.jackson.databind.node.ObjectNode).put("damageType", "directPyro")
        editor.save(FormulaEditorRequestV2("skirk.json", mapper.writeValueAsString(tree), current.getValue("revision")))
        val formulas = DamageFormulaServiceV2(mapper)
        val profile = assertNotNull(formulas.profile("skirk"))
        val type = profile.damageTypes.first { it.id == "directPyro" }
        assertEquals("enemyPyroResistance", type.resistanceInput)
        assertEquals("pyroDamageBonus", type.elementalBonusInput)
        assertEquals("stat:pyro_dmg_", profile.inputs.first { it.id == "pyroDamageBonus" }.initialValueSource?.valueKey)
        val result = formulas.calculate(profile, DamageCalculationRequestV2(inputs = mapOf(
            "attack" to 1000.0, "elementalDamageBonus" to 900.0, "pyroDamageBonus" to 50.0,
            "enemyResistance" to 90.0, "enemyPyroResistance" to 10.0), enabledEffects = profile.effects.associate { it.id to false }))
            .results.first { it.formulaId == hit["id"].asText() }
        assertEquals(1.5, result.damageBonus.multiplier, 1e-8)
        assertEquals(.9, result.resistanceMitigation.multiplier, 1e-8)
        assertFalse(mapper.readTree(editor.read("skirk.json").getValue("content")).has("damageTypes"))
    }

    @Test fun `unknown type selection is rejected before saving`() {
        val current = editor.read("nefer.json")
        val broken = current.getValue("content").replace("\"damageType\": \"lunarBloom\"", "\"damageType\": \"doesNotExist\"")
        assertContains(assertFailsWith<IllegalArgumentException> {
            editor.save(FormulaEditorRequestV2("nefer.json", broken, current.getValue("revision")))
        }.message.orEmpty(), "doesNotExist")
        assertEquals(current, editor.read("nefer.json"))
    }

    @Test fun `a character needs no authored inputs to use stats talent levels and constellation`() {
        Files.writeString(directory.resolve("fresh.json"), """{
            "schemaVersion":3,"key":"fresh","characterKey":"fresh","name":"Fresh","element":"Cryo",
            "characterDataResource":"data/characters/data/skirk.json",
            "talents":[{"key":"skill","name":"Skill","formulas":[{"id":"hit","label":"Hit","damageType":"directCryo",
                "base":{"type":"multiply","operands":[{"type":"input","input":"source:attack"},
                {"type":"talentParameter","talent":"combat2","parameter":"param1","levelInput":"source:skillTalentLevel"}]}}]}],
            "effects":[{"id":"c1","name":"C1","source":"Fresh","description":"Test","category":"constellation",
                "ownershipRequirements":{"source:constellation":1},"activation":{"event":"always"}}]
        }""")
        val formulas = DamageFormulaServiceV2(mapper)
        val profile = assertNotNull(formulas.profile("fresh"))
        for (id in listOf("characterLevel", "normalTalentLevel", "skillTalentLevel", "burstTalentLevel", "constellation", "attack", "critRate")) {
            assertTrue(profile.inputs.any { it.id == id }, "Missing inherited $id")
        }
        val result = formulas.calculate(profile, DamageCalculationRequestV2(inputs = mapOf("attack" to 2000.0, "skillTalentLevel" to 10.0, "constellation" to 1.0)))
        assertTrue(result.results.single().base.value > 0.0)
        assertTrue("c1" in result.activeEffectIds)
        editor.validate(FormulaEditorRequestV2("fresh.json", editor.read("fresh.json").getValue("content")))
    }

    @Test fun `editor exposes inherited levels even when a character has no special inputs`() {
        val catalog = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(editor.catalog())
        val lauma = catalog.first { it["key"].asText() == "lauma" }
        val levels = lauma["inputs"].filter { it.path("initialValueSource").path("valueKey").asText().endsWith("Talent") }
        assertEquals(3, levels.size)
        assertTrue(levels.any { it["id"].asText() == "laumaBurstTalentLevel" && it["defaultValue"].asDouble() == 10.0 })
        val standards = editor.standardInputs().getValue("Dendro")
        assertEquals(6.0, standards.first { it.id == "constellation" }.max)
        val authored = mapper.readTree(editor.read("lauma.json").getValue("content"))
        assertTrue(authored["inputs"].isEmpty)
        assertEquals(1000.0, authored["inputDefaults"]["elementalMastery"].asDouble())
    }

    @Test fun `standard preview defaults are optional validated and do not create custom inputs`() {
        val current = editor.read("nefer.json")
        val tree = mapper.readTree(current.getValue("content"))
        val defaults = tree["inputDefaults"] as com.fasterxml.jackson.databind.node.ObjectNode
        defaults.put("skillTalentLevel", 7.0)
        editor.save(FormulaEditorRequestV2("nefer.json", mapper.writeValueAsString(tree), current.getValue("revision")))
        assertEquals(7.0, DamageFormulaServiceV2(mapper).profile("nefer")!!.inputs.first { it.id == "skillTalentLevel" }.defaultValue)
        assertEquals(listOf("skillActive", "veilStacks"), tree["inputs"].map { it["id"].asText() })
        defaults.put("skillTalentLevel", 99.0)
        assertFailsWith<IllegalArgumentException> { editor.validate(FormulaEditorRequestV2("nefer.json", mapper.writeValueAsString(tree))) }
        defaults.put("skillTalentLevel", 7.0)
        defaults.put("unknownStat", 1.0)
        assertFailsWith<IllegalArgumentException> { editor.validate(FormulaEditorRequestV2("nefer.json", mapper.writeValueAsString(tree))) }
    }
}
