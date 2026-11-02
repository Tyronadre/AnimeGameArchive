package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DamageFormulaTeamServiceV2Test {
    private val mapper = jacksonObjectMapper()
    private val service = DamageFormulaServiceV2(mapper)
    private val teams = DamageFormulaTeamServiceV2(mapper)
    private val base = assertNotNull(service.profile("nefer"))
    private val full = teams.compose(base)

    @Test
    fun `default party is composed from discovered support files`() {
        assertEquals(setOf("nefer", "lauma", "nahida", "columbina"), full.selectedTeam)
        assertEquals(2.0, full.teamContext["moonsignLevel"])
        assertEquals(3.0, full.teamContext["dendroCount"])
        assertEquals(4, full.teamMembers.count { it.key in full.selectedTeam })
        assertTrue(full.teamMembers.first { it.key == "nefer" }.calculatorAvailable)
        assertFalse(full.teamMembers.first { it.key == "lauma" }.calculatorAvailable)
        assertTrue(full.teamMembers.first { it.key == "arlecchino" }.calculatorAvailable)
        full.supportProfiles.forEach { support ->
            assertEquals(6, support.effects.count { it.category == "constellation" })
            assertTrue(support.sources.isNotEmpty())
            support.inputs.filter { it.initialValueSource != null }.forEach { authored ->
                assertEquals(support.key, full.inputs.first { it.id == authored.id }.initialValueSource?.characterKey)
            }
        }
        assertEquals(4, full.teamStatInputs.getValue("elementalMastery").size)
        assertEquals(setOf("nefer", "lauma", "nahida", "columbina"), full.characterStatInputs.keys)
        assertTrue(full.characterStatInputs.values.all { it.keys == setOf(
            "maxHp", "attack", "defense", "elementalMastery", "critRate", "critDamage",
            "energyRecharge", "elementalDamageBonus", "physicalDamageBonus",
        ) })
        full.characterStatInputs.forEach { (character, stats) ->
            stats.values.forEach { inputId ->
                assertEquals("stats:$character", full.inputs.first { it.id == inputId }.section)
            }
        }
        assertTrue(full.characterProgressInputs.values.all { it.keys == setOf(
            "characterLevel", "normalTalentLevel", "skillTalentLevel", "burstTalentLevel",
        ) })
        full.characterProgressInputs.forEach { (character, progress) ->
            progress.values.forEach { inputId ->
                assertEquals("progress:$character", full.inputs.first { it.id == inputId }.section)
            }
        }
        assertEquals(emptyList(), full.characterBuffInputs.getValue("lauma"))
        assertEquals(setOf("character.nahida.nahidaMarkedOpponents", "character.nahida.nahidaQuickenActive"),
            full.characterBuffInputs.getValue("nahida").toSet())
        assertEquals(emptyList(), full.characterBuffInputs.getValue("columbina"))
        assertEquals("internal", full.inputs.first { it.id == "character.columbina.columbinaBaseHp" }.section)
        assertTrue(service.document(full).defaultCalculation.results.isNotEmpty())
    }

    @Test
    fun `catalog characters remain selectable without invented support effects`() {
        val roster = mock(DamageTeamRosterService::class.java)
        `when`(roster.members()).thenReturn(listOf(
            DamageTeamMemberV2("amber", "Amber", "Pyro", 0, "/media/characters/amber/icon", "Bow"),
        ))
        val profile = DamageFormulaTeamServiceV2(mapper, roster).compose(base, setOf("amber"))
        assertEquals(setOf("nefer", "amber"), profile.selectedTeam)
        assertEquals("/media/characters/amber/icon", profile.teamMembers.first { it.key == "amber" }.iconUrl)
        assertEquals(1.0, profile.teamContext["pyroCount"])
        assertEquals(1.0, profile.teamContext["dendroCount"])
        assertEquals(2.0, profile.teamContext["teamSize"])
        assertTrue(profile.supportProfiles.all { it.effects.isEmpty() })
        assertFalse(profile.effects.any { it.id.startsWith("amber") })
        assertEquals(9, profile.characterStatInputs.getValue("amber").size)
        assertEquals(4, profile.characterProgressInputs.getValue("amber").size)
        assertTrue(profile.characterBuffInputs.getValue("amber").isEmpty())
        val amberAttack = profile.characterStatInputs.getValue("amber").getValue("attack")
        val edited = service.calculate(profile, DamageCalculationRequestV2(inputs = mapOf(amberAttack to 4321.0)))
        assertEquals(4321.0, edited.effectiveStats.getValue(amberAttack))
    }

    @Test
    fun `removing teammates removes their inputs and effects and changes team conditions`() {
        val solo = teams.compose(base, emptySet())
        assertEquals(setOf("nefer"), solo.selectedTeam)
        assertEquals(1.0, solo.teamContext["moonsignLevel"])
        assertFalse(solo.effects.any { it.id.startsWith("lauma") })
        assertFalse(solo.inputs.any { it.id.startsWith("lauma") })
        val result = service.calculate(solo, DamageCalculationRequestV2(
            inputs = mapOf("character.lauma.laumaElementalMastery" to 3000.0), enabledEffects = mapOf("character.lauma.laumaPaleHymn" to true),
        ))
        assertFalse("character.lauma.laumaPaleHymn" in result.activeEffectIds)
        assertFalse("dendroResonance" in result.activeEffectIds)
        assertFalse("character.lauma.laumaElementalMastery" in result.effectiveStats)
        assertFailsWith<IllegalArgumentException> { teams.compose(base, setOf("unknown")) }
        assertFailsWith<IllegalArgumentException> { teams.compose(base, setOf("lauma", "nahida", "columbina", "unknown")) }
    }

    @Test
    fun `every supported party subset calculates and requires actual Moonsign members`() {
        val keys = listOf("lauma", "nahida", "columbina")
        for (mask in 0 until 8) {
            val selected = keys.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val profile = teams.compose(base, selected)
            val result = service.calculate(profile, DamageCalculationRequestV2(
                enabledEffects = profile.effects.associate { it.id to true },
            ))
            assertTrue(result.results.all { it.average.isFinite() && it.average >= 0.0 })
            if (selected.none { it == "lauma" || it == "columbina" }) {
                assertFalse("character.nahida.nahidaNewMoonsEmbrace" in result.activeEffectIds)
                assertEquals(1.0, result.hit("shades-hit-1").elevation.multiplier)
            }
        }
    }

    @Test
    fun `team order cannot change converted stats or damage`() {
        val reversed = teams.compose(base, linkedSetOf("columbina", "nahida", "lauma", "nefer"))
        val request = DamageCalculationRequestV2(enabledEffects = full.effects.associate { it.id to true })
        assertEquals(service.calculate(full, request), service.calculate(reversed, request))
    }

    @Test
    fun `disabling all composed effects reproduces the solo pipeline`() {
        val party = calculate()
        val solo = service.calculate(base, DamageCalculationRequestV2(
            enabledEffects = base.effects.associate { it.id to false },
        ))
        assertEquals(solo.results.map { it.copy(reactions = emptyList()) }, party.results.map { it.copy(reactions = emptyList()) })
    }

    @Test
    fun `Lauma scaling uses her own talent levels and the currently authored post bonus layer`() {
        val inputs = mapOf("character.lauma.laumaElementalMastery" to 1000.0, "character.lauma.laumaBurstTalentLevel" to 10.0,
            "character.lauma.laumaSkillTalentLevel" to 10.0, "elementalMastery" to 1000.0)
        val result = calculate(inputs, "character.lauma.laumaPaleHymn", "character.lauma.laumaC2", "character.lauma.laumaC3", "character.lauma.laumaC5", "character.lauma.laumaFrostgroveSanctuary")
        val shade = result.hit("shades-hit-1")
        assertEquals(13.0, result.effectiveStats["character.lauma.laumaBurstTalentLevel"])
        assertEquals(13.0, result.effectiveStats["character.lauma.laumaSkillTalentLevel"])
        assertEquals(0.0, shade.base.additiveValue, 1e-6)
        assertEquals(8722.6, shade.postBonusBase.value, 1e-6)
        assertEquals(.34, shade.resistanceMitigation.reduction, 1e-6)
        assertTrue(shade.postBonusBase.references.any { it.characterKey == "lauma" && it.talentLevel == 13 })
        assertEquals((shade.base.value * shade.damageBonus.multiplier + 8722.6) *
            shade.resistanceMitigation.multiplier, shade.nonCritical, 1e-6)
        assertEquals(0.0, result.hit("skill-hit").postBonusBase.value)
        val noStacks = calculate(inputs, "character.lauma.laumaC2")
        assertEquals(0.0, noStacks.hit("shades-hit-1").postBonusBase.value)
        assertEquals(.4, noStacks.hit("shades-hit-1").damageBonus.authoredBonus, 1e-6)
    }

    @Test
    fun `Nahida uses highest party EM and conversions do not feed themselves`() {
        val result = calculate(mapOf("elementalMastery" to 800.0, "character.nahida.nahidaElementalMastery" to 500.0,
            "character.lauma.laumaElementalMastery" to 600.0, "character.columbina.columbinaElementalMastery" to 0.0), "character.nahida.nahidaCompassionIlluminated")
        assertEquals(1000.0, result.effectiveStats["elementalMastery"])
        assertEquals(200.0, result.statChanges.single().amount)
        assertTrue(result.statChanges.single().references.any { it.key == "elementalMastery" && it.value == 800.0 })
        val capped = calculate(mapOf("character.lauma.laumaElementalMastery" to 2000.0), "character.nahida.nahidaCompassionIlluminated")
        assertEquals(250.0, capped.statChanges.single().amount)
    }

    @Test
    fun `Nahida C4 is resolved before EM share and marking gates C2`() {
        val result = calculate(mapOf("elementalMastery" to 0.0, "character.lauma.laumaElementalMastery" to 0.0,
            "character.nahida.nahidaElementalMastery" to 500.0, "character.nahida.nahidaMarkedOpponents" to 8.0),
            "character.nahida.nahidaC4", "character.nahida.nahidaC2", "character.nahida.nahidaCompassionIlluminated", "character.nahida.nahidaNewMoonsEmbrace")
        assertEquals(660.0, result.effectiveStats["character.nahida.nahidaElementalMastery"])
        assertEquals(165.0, result.effectiveStats["elementalMastery"])
        assertEquals(.1485, result.hit("shades-hit-1").damageBonus.authoredBonus, 1e-6)
        assertEquals(.1, result.hit("shades-hit-1").crit.additionalRate)
        assertEquals(.2, result.hit("shades-hit-1").crit.additionalDamage)
        assertEquals(0.0, result.hit("skill-hit").crit.additionalRate)
        val unmarked = calculate(mapOf("character.nahida.nahidaMarkedOpponents" to 0.0), "character.nahida.nahidaC2", "character.nahida.nahidaC4")
        assertFalse("character.nahida.nahidaC2" in unmarked.activeEffectIds)
        assertFalse("character.nahida.nahidaC4" in unmarked.activeEffectIds)
    }

    @Test
    fun `Columbina C2 applies percent of base HP before HP to EM conversion`() {
        val result = calculate(mapOf("character.columbina.columbinaMaxHp" to 35000.0, "character.columbina.columbinaBaseHp" to 14695.0,
            "elementalMastery" to 0.0), "character.columbina.columbinaC2")
        assertEquals(40878.0, result.effectiveStats["character.columbina.columbinaMaxHp"])
        assertEquals(40878.0 * .0035, result.effectiveStats.getValue("elementalMastery"), 1e-6)
        assertEquals(1.07, result.hit("shades-hit-1").elevation.multiplier, 1e-6)
    }

    @Test
    fun `Columbina passive reads the editable Max HP combat stat`() {
        val lowHp = calculate(mapOf("character.columbina.columbinaMaxHp" to 10000.0), "character.columbina.columbinaMoonlightLent")
        val capped = calculate(mapOf("character.columbina.columbinaMaxHp" to 35000.0), "character.columbina.columbinaMoonlightLent")
        assertEquals(1.02, lowHp.hit("shades-hit-1").base.multiplier, 1e-6)
        assertEquals(1.07, capped.hit("shades-hit-1").base.multiplier, 1e-6)
        assertTrue(capped.hit("shades-hit-1").base.value > lowHp.hit("shades-hit-1").base.value)
    }

    @Test
    fun `team base bonuses and Elevation add while crit caps and Domain dependency are respected`() {
        val result = calculate(emptyMap(), "dusklitEaves", "character.lauma.laumaNaturesChorus", "character.columbina.columbinaMoonlightLent",
            "neferC6", "character.lauma.laumaC6", "character.columbina.columbinaC1", "character.columbina.columbinaC2", "character.columbina.columbinaC3", "character.columbina.columbinaC4", "character.columbina.columbinaC5", "character.columbina.columbinaC6",
            "character.columbina.columbinaLunarDomain", "character.lauma.laumaLightForTheFrostyNight", "character.nahida.nahidaC2")
        val shade = result.hit("shades-hit-1")
        assertEquals(1.35, shade.base.multiplier, 1e-6)
        assertEquals(1.60, shade.elevation.multiplier, 1e-6)
        assertEquals(1.0, shade.crit.effectiveRate)
        assertEquals(1.2, shade.crit.additionalDamage, 1e-6)
        assertEquals(.49, shade.damageBonus.authoredBonus, 1e-6)
        assertEquals(.8, result.hit("skill-hit").crit.additionalDamage)
        val noDomain = calculate(emptyMap(), "character.columbina.columbinaC6")
        assertEquals(0.0, noDomain.hit("skill-hit").crit.additionalDamage)
        assertEquals(1.07, noDomain.hit("shades-hit-1").elevation.multiplier, 1e-6)
    }

    @Test
    fun `resonance applies once to each member and conditional portions are optional`() {
        val result = calculate(emptyMap(), "dendroResonance")
        full.teamStatInputs.getValue("elementalMastery").forEach { id ->
            assertEquals(result.inputs.getValue(id) + 80.0, result.effectiveStats[id])
        }
        val noReaction = calculate(mapOf("dendroResonanceBloomActive" to 0.0), "dendroResonance")
        assertEquals(noReaction.inputs.getValue("elementalMastery") + 50.0, noReaction.effectiveStats["elementalMastery"])
    }

    @Test
    fun `resolved changes reconstruct every displayed effective combat stat`() {
        val result = service.calculate(full, DamageCalculationRequestV2(
            enabledEffects = full.effects.associate { it.id to true },
        ))
        full.characterStatInputs.values.flatMap { it.values }.forEach { inputId ->
            val resolved = result.inputs.getValue(inputId) + result.statChanges
                .filter { it.stat == inputId }.sumOf { it.amount }
            assertEquals(resolved, result.effectiveStats.getValue(inputId), 1e-8, inputId)
        }
    }

    @Test
    fun `owned constellations default on but explicit previews and exclusions win`() {
        val owned = service.calculate(full, DamageCalculationRequestV2(inputs = mapOf("character.columbina.columbinaConstellation" to 2.0)))
        assertTrue("character.columbina.columbinaC1" in owned.activeEffectIds)
        assertTrue("character.columbina.columbinaC2" in owned.activeEffectIds)
        assertFalse("character.columbina.columbinaC3" in owned.activeEffectIds)
        val preview = service.calculate(full, DamageCalculationRequestV2(
            inputs = mapOf("character.columbina.columbinaConstellation" to 2.0),
            enabledEffects = mapOf("character.columbina.columbinaC1" to false, "character.columbina.columbinaC6" to true),
        ))
        assertFalse("character.columbina.columbinaC1" in preview.activeEffectIds)
        assertTrue("character.columbina.columbinaC6" in preview.activeEffectIds)
    }

    private fun calculate(inputs: Map<String, Double> = emptyMap(), vararg enabled: String): DamageCalculationV2 =
        service.calculate(full, DamageCalculationRequestV2(inputs = inputs,
            enabledEffects = full.effects.associate { it.id to (it.id in enabled) }))

    private fun DamageCalculationV2.hit(id: String) = results.first { it.formulaId == id }
}
