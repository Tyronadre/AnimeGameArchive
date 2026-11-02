package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

class AlphabeticalCharacterProfilesV3Test {
    private val mapper = jacksonObjectMapper()
    private val service = DamageFormulaServiceV2(mapper)
    private val teams = DamageFormulaTeamServiceV2(mapper)
    private val keys = listOf("aino", "albedo", "alhaitham", "aloy", "amber")
    private fun calculate(key: String, values: Map<String, Double>, vararg effects: String): DamageCalculationV2 {
        val p = assertNotNull(service.profile(key))
        return service.calculate(p, DamageCalculationRequestV2(values, p.effects.associate { it.id to (it.id in effects) }))
    }
    @Test fun `each authored profile validates both as damage owner and as teammate`() {
        for (key in keys) {
            service.validateCharacter(key)
            val p = assertNotNull(service.profile(key))
            assertTrue(service.document(teams.compose(p)).defaultCalculation.results.isNotEmpty())
            val other = teams.compose(assertNotNull(service.profile("nefer")), setOf(key))
            assertTrue(service.document(other).defaultCalculation.results.isNotEmpty())
            val all = service.calculate(teams.compose(p), DamageCalculationRequestV2(
                p.statBindings["constellation"]?.let { mapOf(it to 6.0) }.orEmpty(),
                p.effects.associate { it.id to true }))
            assertTrue(all.results.all { it.average.isFinite() })
        }
    }
    @Test fun `Albedo blossoms scale with DEF while the cast scales with ATK`() {
        val a = calculate("albedo", mapOf("attack" to 1000.0, "defense" to 1000.0))
        val b = calculate("albedo", mapOf("attack" to 1000.0, "defense" to 2000.0))
        assertEquals(a.results.first { it.formulaId == "skill-cast" }.base.value, b.results.first { it.formulaId == "skill-cast" }.base.value)
        assertEquals(a.results.first { it.formulaId == "transient-blossom" }.base.value * 2,
            b.results.first { it.formulaId == "transient-blossom" }.base.value, 1e-6)
    }
    @Test fun `infusion replaces physical hits instead of showing both states`() {
        for ((key, effect, suffix) in listOf(Triple("alhaitham", "mirrorInfusion", "-dendro"), Triple("aloy", "rushingIce", "-cryo"))) {
            val normal = calculate(key, emptyMap()).results.filter { it.formulaId.startsWith("normal-") }
            val infused = calculate(key, emptyMap(), effect).results.filter { it.formulaId.startsWith("normal-") }
            assertEquals(normal.size, infused.size)
            assertTrue(normal.none { it.formulaId.endsWith(suffix) })
            assertTrue(infused.all { it.formulaId.endsWith(suffix) })
        }
    }
    @Test fun `Aino EM passive adds flat base damage rather than a percentage bonus`() {
        val values = mapOf("attack" to 1000.0, "elementalMastery" to 1000.0)
        val normal = calculate("aino", values).results.first { it.formulaId == "water-ball" }
        val boosted = calculate("aino", values, "structuredPowerBooster").results.first { it.formulaId == "water-ball" }
        assertEquals(500.0, boosted.base.value - normal.base.value, 1e-6)
        assertEquals(normal.damageBonus.multiplier, boosted.damageBonus.multiplier)
    }
    @Test fun `Amber manual detonation triples the base and replaces the regular explosion`() {
        val values = mapOf("attack" to 1000.0, "constellation" to 2.0)
        val normal = calculate("amber", values).results.first { it.formulaId == "bunny" }
        val result = calculate("amber", values, "amberC2")
        assertFalse(result.results.any { it.formulaId == "bunny" })
        assertEquals(normal.base.value * 3, result.results.first { it.formulaId == "bunny-manual" }.base.value, 1e-6)
    }
}
