package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

class FormerSupportDamageV3Test {
    private val service = DamageFormulaServiceV2(jacksonObjectMapper())
    private fun calculate(key: String, values: Map<String, Double>, vararg enabled: String): DamageCalculationV2 {
        val profile = assertNotNull(service.profile(key))
        val boundValues = values.mapKeys { (name, _) -> profile.statBindings[name] ?: name }
        return service.calculate(profile, DamageCalculationRequestV2(boundValues, profile.effects.associate { it.id to (it.id in enabled) }))
    }
    @Test fun `Lauma direct skill uses ATK and Sanctuary uses both ATK and EM`() {
        val p = assertNotNull(service.profile("lauma"))
        val hits = p.talents.flatMap { it.formulas }
        assertTrue(hits.filter { it.label.contains("Plunge") }.all { "plungingAttack" in it.tags && "chargedAttack" !in it.tags })
        assertEquals("directDendro", hits.first { it.id == "laumaPressDMG" }.damageType)
        val r = calculate("lauma", mapOf("attack" to 1000.0, "elementalMastery" to 500.0, "skillTalentLevel" to 10.0))
        assertEquals(2188.8, r.results.first { it.formulaId == "laumaPressDMG" }.base.value, 1e-6)
        assertEquals(3456.0, r.results.first { it.formulaId == "laumaFrostmoonSanctuaryDMG" }.base.value, 1e-6)
    }
    @Test fun `Lauma held Lunar hit consumes actual Dew capped at three and not Moon Song`() {
        for (dew in listOf(1.0, 2.0, 6.0)) {
            val r = calculate("lauma", mapOf("elementalMastery" to 1000.0, "skillTalentLevel" to 10.0,
                "verdantDew" to dew, "moonSong" to 0.0), "laumaHeldSkillAvailable")
            assertEquals(2736.0 * minOf(3.0, dew), r.results.first { it.formulaId == "lauma1HitHoldDMGCopy" }.base.value, 1e-6)
        }
        assertFalse(calculate("lauma", mapOf("verdantDew" to 0.0), "laumaHeldSkillAvailable").results.any { it.formulaId == "lauma1HitHoldDMGCopy" })
    }
    @Test fun `Nahida mixed scaling and EM passive affect Tri Karma only`() {
        val r = calculate("nahida", mapOf("attack" to 1000.0, "elementalMastery" to 1000.0, "skillTalentLevel" to 10.0), "nahidaAwakeningElucidated")
        val karma = r.results.first { it.formulaId == "tri-karma" }
        assertEquals(5572.8, karma.base.value, 1e-6)
        assertEquals(.8, karma.damageBonus.authoredBonus, 1e-6)
        assertEquals(0.0, r.results.first { it.formulaId == "skill-press" }.damageBonus.authoredBonus, 1e-6)
        assertFalse(r.results.any { it.formulaId == "karmic-oblivion" })
    }
    @Test fun `Columbina uses HP references and individual Lunar hits without multiplying their count twice`() {
        val r = calculate("columbina", mapOf("maxHp" to 40000.0, "skillTalentLevel" to 10.0, "normalTalentLevel" to 10.0))
        assertEquals(12040.0, r.results.first { it.formulaId == "skill-hit" }.base.value, 1e-6)
        val bloom = r.results.filter { it.formulaId.startsWith("gravity-bloom-") }
        assertEquals(5, bloom.size)
        bloom.forEach { assertEquals(1012.0, it.base.value, 1e-6) }
        assertEquals(3, r.results.count { it.formulaId.startsWith("moondew-cleanse-") })
        assertEquals(10164.0, r.results.first { it.formulaId == "gravity-charged" }.base.value, 1e-6)
        assertEquals(10163.2, r.results.first { it.formulaId == "gravity-crystallize" }.base.value, 1e-6)
    }
}
