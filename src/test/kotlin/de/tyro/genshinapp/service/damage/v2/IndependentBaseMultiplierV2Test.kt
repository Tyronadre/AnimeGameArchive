package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

class IndependentBaseMultiplierV2Test {
    private val mapper = jacksonObjectMapper()
    private val service = DamageFormulaServiceV2(mapper)
    private val original = assertNotNull(service.profile("nefer"))
    private fun constant(value: Double) = FormulaExpressionV2("constant", value = value)
    private fun percent(value: Double) = FormulaContributionV2(constant(value))
    private fun effect(id: String, modifier: FormulaModifierV2) = DamageEffectV2(
        id, id, "Test", "Test base layers", activation = EffectActivationV2("always"), formulaModifiers = listOf(modifier),
    )
    private fun calculate(kind: String, independent: Boolean = true): DamageFormulaResultV2 {
        val formula = DamageFormulaV2("testHit", "Test", "testType", base = constant(100.0))
        val profile = original.copy(
            damageTypes = listOf(DamageTypeDefinitionV2("testType", "Dendro", kind = kind)),
            talents = listOf(DamageTalentV2("test", "Test", listOf(formula))),
            effects = listOf(
                effect("first", FormulaModifierV2(FormulaTargetsV2(), base = constant(10.0), baseDamageBonus = percent(20.0))),
                effect("second", FormulaModifierV2(FormulaTargetsV2(), base = constant(20.0), baseDamageBonus = percent(30.0))),
                effect("independent1", FormulaModifierV2(FormulaTargetsV2(), baseDamageMultiplier = percent(40.0))),
                effect("independent2", FormulaModifierV2(FormulaTargetsV2(), baseDamageMultiplier = FormulaContributionV2(constant(0.5), "ratio"))),
                effect("post", FormulaModifierV2(FormulaTargetsV2(), postBonusBase = constant(25.0))),
            ),
        )
        val parsed = mapper.readValue(mapper.writeValueAsString(profile), DamageFormulaProfileV2::class.java)
        return service.calculate(parsed, DamageCalculationRequestV2(enabledEffects = mapOf(
            "independent1" to independent, "independent2" to independent,
        ))).results.single()
    }
    @Test fun `Lunar base bonuses add within a group and multiply independently between groups`() {
        val result = calculate("lunar")
        assertEquals((100.0 + 10.0 + 20.0) * (1.0 + 0.2 + 0.3) * 1.4 * 1.5, result.base.value, 1e-9)
        assertEquals(1.5, result.base.multiplier, 1e-9)
        assertEquals(2.1, result.base.independentMultiplier, 1e-9)
        assertEquals(25.0, result.postBonusBase.value)
        assertEquals((result.base.value * result.damageBonus.multiplier + 25.0) * result.defenseMitigation.multiplier * result.resistanceMitigation.multiplier, result.nonCritical, 1e-9)
    }
    @Test fun `talent independent factors include flat additions while existing bonus ordering stays intact`() {
        assertEquals((100.0 * 1.5 + 10.0 + 20.0) * 1.4 * 1.5, calculate("talent").base.value, 1e-9)
    }
    @Test fun `disabled independent effects preserve previous calculations`() {
        val result = calculate("lunar", false)
        assertEquals(195.0, result.base.value, 1e-9)
        assertEquals(1.0, result.base.independentMultiplier)
    }
    @Test fun `selectors conditions and dependencies gate independent contributions`() {
        val modifier = FormulaModifierV2(FormulaTargetsV2(formulaIds = listOf("skill-hit")),
            conditions = listOf(EffectActivationConditionV2("inputAtLeast", "veilStacks", 4.0)),
            requiresEffects = listOf("unlock"), baseDamageMultiplier = percent(20.0))
        val profile = original.copy(effects = original.effects + listOf(effect("unlock", FormulaModifierV2(FormulaTargetsV2())), effect("bonus", modifier)))
        fun result(stacks: Double, unlock: Boolean) = service.calculate(profile, DamageCalculationRequestV2(
            inputs = mapOf("veilStacks" to stacks), enabledEffects = profile.effects.associate {
                it.id to (it.id == "bonus" || (it.id == "unlock" && unlock))
            }))
        assertEquals(1.0, result(3.0, true).results.first { it.formulaId == "skill-hit" }.base.independentMultiplier)
        assertEquals(1.0, result(4.0, false).results.first { it.formulaId == "skill-hit" }.base.independentMultiplier)
        val active = result(4.0, true)
        assertEquals(1.2, active.results.first { it.formulaId == "skill-hit" }.base.independentMultiplier)
        assertTrue(active.results.filter { it.formulaId != "skill-hit" }.all { it.base.independentMultiplier == 1.0 })
    }
    @Test fun `new contribution expressions are validated`() {
        val profile = original.copy(effects = original.effects + listOf(effect("invalid", FormulaModifierV2(FormulaTargetsV2(),
            baseDamageMultiplier = FormulaContributionV2(FormulaExpressionV2("input", input = "undefinedStat"))))))
        val error = assertFailsWith<IllegalArgumentException> { service.document(profile) }
        assertContains(error.message.orEmpty(), "undefinedStat")
    }
    @Test fun `equipment binder namespaces independent expressions and preserves references`() {
        val base = DamageFormulaTeamServiceV2(mapper).compose(original, emptySet())
        val definition = DamageArtifactSetV2(2, "testset", "Test", inputs = listOf(
            DamageFormulaInputV2("bonus", "Bonus", 20.0, 0.0, 100.0, 1.0)), effects = listOf(
                DamageArtifactEffectV2(2, "wearer", effect("2pc", FormulaModifierV2(FormulaTargetsV2(),
                    baseDamageMultiplier = FormulaContributionV2(FormulaExpressionV2("input", input = "local:bonus")))))))
        val profile = DamageFormulaArtifactServiceV2(mapper).bindEquipment(base, listOf(
            EquippedEffectItemV2("nefer", "testset", "flower"), EquippedEffectItemV2("nefer", "testset", "plume")), mapOf("testset" to definition))
        val result = service.calculate(profile, DamageCalculationRequestV2(enabledEffects = profile.effects.associate {
            it.id to it.id.startsWith("artifact.")
        })).results.first()
        assertEquals(1.2, result.base.independentMultiplier)
        assertTrue(result.base.references.any { it.key == "artifact.nefer.testset.bonus" && it.value == 20.0 })
    }
}
