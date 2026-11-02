package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DamageFormulaServiceV2Test {
    private val service = DamageFormulaServiceV2(jacksonObjectMapper())
    private val profile = assertNotNull(service.profile("nefer"))

    @Test
    fun `Nefer V2 profile is explicitly authored by formula layer`() {
        assertEquals(2, profile.schemaVersion)
        assertEquals(listOf(8, 8, 2), profile.talents.map { it.formulas.size })
        assertEquals(listOf("directDendro", "lunarBloom"), profile.damageTypes.map { it.id })
        assertEquals(
            listOf("passive", "passive", "passive") + List(6) { "constellation" },
            profile.effects.map { it.category },
        )
        assertEquals(
            "totalAttack",
            profile.inputs.first { it.id == "attack" }.initialValueSource?.valueKey,
        )
        assertFalse(profile.inputs.any { it.id == "otherDamageBonus" })

        val skill = formula("skill-hit")
        assertEquals("directDendro", skill.damageType)
        assertNull(skill.damageBonus)
        assertNull(skill.defenseMitigation)
        assertNull(skill.resistanceMitigation)
        assertNull(skill.crit)
        assertTrue(skill.base.containsTalentReference("combat2", "param1"))
        assertTrue(skill.base.containsTalentReference("combat2", "param2"))

        val shade = formula("shades-hit-1")
        assertEquals("lunarBloom", shade.damageType)
        assertEquals("bypass", profile.damageTypes.first { it.id == shade.damageType }.defenseBehavior)
        assertFalse(profile.damageTypes.first { it.id == shade.damageType }.appliesElementalDamageBonus)
    }

    @Test
    fun `talent parameter references resolve at the selected talent level`() {
        val levelOne = calculation(
            inputs = mapOf(
                "attack" to 1300.0,
                "elementalMastery" to 1000.0,
                "skillTalentLevel" to 1.0,
            ),
            passiveEnabled = false,
        ).result("skill-hit")
        val levelTen = calculation(
            inputs = mapOf(
                "attack" to 1300.0,
                "elementalMastery" to 1000.0,
                "skillTalentLevel" to 10.0,
            ),
            passiveEnabled = false,
        ).result("skill-hit")

        assertEquals(1300.0 * 0.76384 + 1000.0 * 1.52768, levelOne.base.value, 0.0001)
        assertEquals(1300.0 * 1.374912 + 1000.0 * 2.749824, levelTen.base.value, 0.0001)
        assertEquals(
            listOf(1, 1),
            levelOne.base.references
                .filter { it.type == "talentParameter" }
                .map { it.talentLevel },
        )
    }

    @Test
    fun `A Wager applies its stat change and only its targeted base bonus`() {
        val disabled = calculation(
            inputs = mapOf(
                "elementalMastery" to 1000.0,
                "skillTalentLevel" to 1.0,
                "veilStacks" to 3.0,
            ),
            passiveEnabled = false,
        )
        val enabled = calculation(
            inputs = mapOf(
                "elementalMastery" to 1000.0,
                "skillTalentLevel" to 1.0,
                "veilStacks" to 3.0,
            ),
            passiveEnabled = true,
        )

        assertEquals(1000.0, disabled.effectiveStats.getValue("elementalMastery"))
        assertEquals(1100.0, enabled.effectiveStats.getValue("elementalMastery"))
        assertEquals(0.0, enabled.result("skill-hit").damageBonus.authoredBonus, 0.0001)
        assertEquals(1.24, enabled.result("nefer-hit-1").base.independentMultiplier, 0.0001)
        assertEquals(setOf("aWagerOfMoonlight"), enabled.result("nefer-hit-1").appliedEffectIds)
        assertTrue(enabled.result("nefer-hit-1").nonCritical > disabled.result("nefer-hit-1").nonCritical)
    }

    @Test
    fun `Lunar Bloom damage type bypasses defense and excludes Dendro bonus`() {
        val result = calculation(
            inputs = mapOf(
                "characterLevel" to 90.0,
                "enemyLevel" to 90.0,
                "elementalMastery" to 1000.0,
                "skillTalentLevel" to 1.0,
                "elementalDamageBonus" to 100.0,
                "enemyResistance" to 10.0,
            ),
            passiveEnabled = false,
        )
        val direct = result.result("skill-hit")
        val shade = result.result("shades-hit-1")

        assertEquals(0.5, direct.defenseMitigation.multiplier, 0.0001)
        assertEquals(1.0, shade.defenseMitigation.multiplier, 0.0001)
        assertTrue(shade.defenseMitigation.bypassed)
        assertEquals(1.0, direct.damageBonus.elementalBonus, 0.0001)
        assertEquals(0.0, shade.damageBonus.elementalBonus, 0.0001)
        assertEquals(2.0, shade.damageBonus.innateBonus, 0.0001)
        assertEquals(960.0 * 3.0 * 0.9, shade.nonCritical, 0.0001)
    }

    @Test
    fun `Lunar Bloom EM bonus uses six EM over EM plus two thousand`() {
        listOf(0.0, 500.0, 1000.0, 2000.0).forEach { elementalMastery ->
            val shade = calculation(
                inputs = mapOf(
                    "elementalMastery" to elementalMastery,
                    "skillTalentLevel" to 1.0,
                ),
                passiveEnabled = false,
            ).result("shades-hit-1")

            val expectedRatio = (6.0 * elementalMastery) / (elementalMastery + 2000.0)
            assertEquals(expectedRatio, shade.damageBonus.innateBonus, 0.0001)
        }
    }

    @Test
    fun `Moonsign and C1 apply in the authored Lunar Base DMG order`() {
        val shade = calculationWithEffects(
            inputs = mapOf(
                "elementalMastery" to 1000.0,
                "skillTalentLevel" to 1.0,
            ),
            enabledEffects = mapOf(
                "dusklitEaves" to true,
                "neferC1" to true,
            ),
        ).result("shades-hit-1")

        assertEquals(960.0, shade.base.formulaValue, 0.0001)
        assertEquals(600.0, shade.base.additiveValue, 0.0001)
        assertEquals(1.14, shade.base.multiplier, 0.0001)
        assertEquals((960.0 + 600.0) * 1.14, shade.base.value, 0.0001)
    }

    @Test
    fun `A Wager and C2 respect their separate Veil thresholds`() {
        val twoVeils = calculationWithEffects(
            inputs = mapOf("elementalMastery" to 1000.0, "veilStacks" to 2.0),
            enabledEffects = mapOf("aWagerOfMoonlight" to true, "neferC2" to true),
        )
        val fiveVeils = calculationWithEffects(
            inputs = mapOf("elementalMastery" to 1000.0, "veilStacks" to 5.0),
            enabledEffects = mapOf("aWagerOfMoonlight" to true, "neferC2" to true),
        )

        assertEquals(1000.0, twoVeils.effectiveStats.getValue("elementalMastery"), 0.0001)
        assertEquals(1.16, twoVeils.result("nefer-hit-1").base.independentMultiplier, 0.0001)
        assertEquals(1200.0, fiveVeils.effectiveStats.getValue("elementalMastery"), 0.0001)
        assertEquals(1.40, fiveVeils.result("nefer-hit-1").base.independentMultiplier, 0.0001)
    }

    @Test
    fun `C3 C4 and C5 modify their dedicated formula layers`() {
        val result = calculationWithEffects(
            inputs = mapOf(
                "skillTalentLevel" to 10.0,
                "burstTalentLevel" to 10.0,
                "enemyResistance" to 10.0,
            ),
            enabledEffects = mapOf(
                "neferC3" to true,
                "neferC4" to true,
                "neferC5" to true,
            ),
        )

        assertEquals(13.0, result.effectiveStats.getValue("skillTalentLevel"), 0.0001)
        assertEquals(13.0, result.effectiveStats.getValue("burstTalentLevel"), 0.0001)
        assertEquals(0.20, result.result("skill-hit").resistanceMitigation.reduction, 0.0001)
        assertEquals(1.05, result.result("skill-hit").resistanceMitigation.multiplier, 0.0001)
        assertTrue(result.result("skill-hit").base.references.any { it.talentLevel == 13 })
        assertTrue(result.result("burst-hit-1").base.references.any { it.talentLevel == 13 })
    }

    @Test
    fun `C6 replaces the second Nefer hit but needs Ascendant Gleam for Elevation`() {
        val result = calculationWithEffects(
            inputs = mapOf("elementalMastery" to 1000.0),
            enabledEffects = mapOf("neferC6" to true),
        )
        val ids = result.results.map { it.formulaId }

        assertFalse("nefer-hit-2" in ids)
        assertTrue("nefer-hit-2-c6" in ids)
        assertTrue("phantasm-c6-extra" in ids)
        assertEquals(1.0, result.result("shades-hit-1").elevation.multiplier, 0.0001)
        assertEquals(850.0, result.result("nefer-hit-2-c6").base.value, 0.0001)
        assertEquals(1200.0, result.result("phantasm-c6-extra").base.value, 0.0001)
    }

    @Test
    fun `passive activation state remains independently toggleable`() {
        val conditionOff = assertNotNull(
            service.calculate(
                "nefer",
                DamageCalculationRequestV2(inputs = mapOf("skillActive" to 0.0)),
            ),
        )
        val previewOn = assertNotNull(
            service.calculate(
                "nefer",
                DamageCalculationRequestV2(
                    inputs = mapOf("skillActive" to 1.0),
                    enabledEffects = mapOf("aWagerOfMoonlight" to true),
                ),
            ),
        )

        assertTrue("aWagerOfMoonlight" !in conditionOff.activeEffectIds)
        assertTrue("aWagerOfMoonlight" in previewOn.activeEffectIds)
    }

    private fun calculation(
        inputs: Map<String, Double>,
        passiveEnabled: Boolean,
    ): DamageCalculationV2 = calculationWithEffects(
        inputs = inputs,
        enabledEffects = mapOf("aWagerOfMoonlight" to passiveEnabled),
    )

    private fun calculationWithEffects(
        inputs: Map<String, Double>,
        enabledEffects: Map<String, Boolean>,
    ): DamageCalculationV2 = assertNotNull(
        service.calculate(
            "nefer",
            DamageCalculationRequestV2(
                inputs = inputs,
                enabledEffects = profile.effects.associate { effect -> effect.id to false } + enabledEffects,
            ),
        ),
    )

    private fun formula(id: String): DamageFormulaV2 = profile.talents
        .flatMap(DamageTalentV2::formulas)
        .first { it.id == id }

    private fun DamageCalculationV2.result(id: String): DamageFormulaResultV2 =
        results.first { it.formulaId == id }

    private fun FormulaExpressionV2.containsTalentReference(
        talent: String,
        parameter: String,
    ): Boolean = (type == "talentParameter" && this.talent == talent && this.parameter == parameter) ||
        operands.any { it.containsTalentReference(talent, parameter) }
}
