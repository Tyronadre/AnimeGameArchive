package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Regression coverage for damage-layer and team-buff boundaries. */
class DamageFormulaRegressionTest {
    @Test fun `C1 preview raises Fanfare cap without adding to current points`() {
        val p = profile("skirk", setOf("furina"))
        val partial = calculate(p, mapOf("character.furina.fanfarePoints" to 200.0), "character.furina.universalRevelry", "character.furina.furinaC1")
        assertEquals(0.5, partial.hit("warp-normal-1").damageBonus.authoredBonus, 1e-6)
        val full = calculate(p, mapOf("character.furina.fanfarePoints" to 400.0), "character.furina.universalRevelry", "character.furina.furinaC1")
        assertEquals(1.0, full.hit("warp-normal-1").damageBonus.authoredBonus, 1e-6)
    }

    @Test fun `Hydro resonance uses base HP and requires a full party`() {
        val p = profile("furina", setOf("xingqiu", "skirk", "citlali"))
        val result = calculate(p, mapOf("maxHp" to 30000.0, "baseHp" to 15000.0), "hydroResonance")
        assertEquals(33750.0, result.effectiveStats.getValue("maxHp"), 1e-6)
        val incomplete = profile("furina", setOf("xingqiu"))
        assertEquals(30000.0, calculate(incomplete, mapOf("maxHp" to 30000.0), "hydroResonance").effectiveStats.getValue("maxHp"), 1e-6)
    }

    @Test fun `Cryo resonance requires a Cryo affected enemy`() {
        val p = profile("skirk")
        val inactive = calculate(p, mapOf("critRate" to 50.0), "cryoResonance")
        val active = calculate(p, mapOf("critRate" to 50.0, "cryoResonanceActive" to 1.0), "cryoResonance")
        assertEquals(0.5, inactive.hit("warp-normal-1").crit.effectiveRate, 1e-6)
        assertEquals(0.65, active.hit("warp-normal-1").crit.effectiveRate, 1e-6)
    }
    private val mapper = jacksonObjectMapper()
    private val calculator = DamageFormulaServiceV2(mapper)
    private val teams = DamageFormulaTeamServiceV2(mapper)
    private fun profile(key: String, members: Set<String>? = null) =
        teams.compose(assertNotNull(calculator.profile(key)), members)
    private fun calculate(profile: DamageFormulaProfileV2, inputs: Map<String, Double> = emptyMap(), vararg enabled: String) =
        calculator.calculate(profile, DamageCalculationRequestV2(inputs,
            profile.effects.associate { it.id to (it.id in enabled) }))
    private fun DamageCalculationV2.hit(id: String) = results.first { it.formulaId == id }

    @Test fun `Furina ordinary damage bonus must not increase Lunar Bloom`() {
        val p = profile("nefer", setOf("furina"))
        val plain = calculate(p).hit("shades-hit-1")
        val buffed = calculate(p, emptyMap(), "character.furina.universalRevelry").hit("shades-hit-1")
        assertEquals(plain.average, buffed.average, 1e-6)
    }

    @Test fun `Citlali ordinary additive buff must not increase Lunar Bloom`() {
        val p = profile("nefer", setOf("citlali"))
        val plain = calculate(p).hit("shades-hit-1")
        val buffed = calculate(p, emptyMap(), "character.citlali.citlaliC1").hit("shades-hit-1")
        assertEquals(plain.average, buffed.average, 1e-6)
    }

    @Test fun `full double Pyro team must add resonance attack`() {
        val p = profile("arlecchino")
        val inputs = mapOf("attack" to 1000.0, "baseAttack" to 1000.0)
        val disabled = p.effects.filter { it.id !in setOf("pyroResonance", "hydroResonance", "cryoResonance") }.associate { it.id to false }
        val result = calculator.calculate(p, DamageCalculationRequestV2(inputs, disabled))
        assertEquals(1250.0, result.effectiveStats.getValue("attack"), 1e-6)
    }

    @Test fun `Furina C0 cannot use 400 Fanfare`() {
        val p = profile("skirk", setOf("furina"))
        val result = calculate(p, mapOf("character.furina.fanfarePoints" to 400.0, "character.furina.burstTalentLevel" to 10.0), "character.furina.universalRevelry")
        assertEquals(0.75, result.hit("warp-normal-1").damageBonus.authoredBonus, 1e-6)
    }

    @Test fun `Bennett C1 must stop buffing when his field is disabled`() {
        val p = profile("arlecchino", setOf("bennett"))
        val result = calculate(p, mapOf("attack" to 1000.0, "character.bennett.baseAttack" to 800.0), "character.bennett.bennettC1", "character.bennett.bennettC6")
        assertEquals(1000.0, result.effectiveStats.getValue("attack"), 1e-6)
    }

    @Test fun `defense reduction must be capped at 90 percent after stacking`() {
        val p = profile("nefer", setOf("nahida"))
        val result = calculate(p, mapOf("defenseReduction" to 100.0, "character.nahida.nahidaQuickenActive" to 1.0), "character.nahida.nahidaC2")
        val hit = result.hit("skill-hit")
        assertEquals(1.0 / 1.1, hit.defenseMitigation.multiplier, 1e-6)
    }

    @Test fun `Skirk base multiplier must not multiply Escoffier additive damage`() {
        val p = profile("skirk")
        val result = calculate(p, mapOf("attack" to 2000.0, "character.escoffier.attack" to 1800.0, "deathsCrossingStacks" to 3.0), "returnToOblivion", "character.escoffier.escoffierC2")
        val base = result.hit("warp-normal-1").base
        val expected = base.formulaValue * 1.7 + 1800.0 * 2.4
        assertEquals(expected, base.value, 1e-6)
    }
}
