package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

class CharacterBatchBV3Test {
    private val mapper = jacksonObjectMapper()
    private val service = DamageFormulaServiceV2(mapper)
    private val teams = DamageFormulaTeamServiceV2(mapper)
    private fun calculate(key: String, values: Map<String, Double>, vararg enabled: String): DamageCalculationV2 {
        val p = assertNotNull(service.profile(key))
        return service.calculate(p, DamageCalculationRequestV2(values, p.effects.associate { it.id to (it.id in enabled) }))
    }
    @Test fun `four profiles validate as owners teammates and with unlocked constellations`() {
        for (key in listOf("aratakiitto", "baizhu", "barbara", "beidou")) {
            service.validateCharacter(key)
            val p = assertNotNull(service.profile(key))
            assertTrue(service.document(teams.compose(p)).defaultCalculation.results.isNotEmpty())
            val party = teams.compose(assertNotNull(service.profile("nefer")), setOf(key))
            assertTrue(service.document(party).defaultCalculation.results.isNotEmpty())
            val unlocked = service.calculate(p, DamageCalculationRequestV2(mapOf("constellation" to 6.0), p.effects.associate { it.id to true }))
            assertTrue(unlocked.results.all { it.average.isFinite() })
        }
    }
    @Test fun `Itto converts DEF to ATK before Ushi and adds DEF only to Kesagiri`() {
        val r = calculate("aratakiitto", mapOf("attack" to 1000.0, "defense" to 2000.0, "burstTalentLevel" to 10.0), "ragingOniKing", "crimsonOni")
        assertEquals(3073.6, r.effectiveStats["attack"]!!, 1e-6)
        assertEquals(700.0, r.results.first { it.formulaId == "kesagiri-combo-geo" }.base.additiveValue, 1e-6)
        assertEquals(0.0, r.results.first { it.formulaId == "saichimonji-geo" }.base.additiveValue)
        assertEquals(3073.6 * 5.5296, r.results.first { it.formulaId == "ushi" }.base.formulaValue, 1e-6)
        assertFalse(r.results.any { it.formulaId == "normal-1" })
    }
    @Test fun `Baizhu Lunar bonus caps at fifty thousand HP and C6 is flat HP scaling`() {
        for (hp in listOf(50000.0, 80000.0)) {
            val p = teams.compose(assertNotNull(service.profile("nefer")), setOf("baizhu"))
            val r = service.calculate(p, DamageCalculationRequestV2(mapOf("character.baizhu.maxHp" to hp),
                p.effects.associate { it.id to (it.id == "character.baizhu.verdantFavor") }))
            assertEquals(.35, r.results.first { it.formulaId == "shades-hit-1" }.damageBonus.authoredBonus, 1e-6)
        }
        val r = calculate("baizhu", mapOf("maxHp" to 50000.0, "constellation" to 6.0), "baizhuC6")
        assertEquals(4000.0, r.results.first { it.formulaId == "spiritvein" }.base.additiveValue, 1e-6)
        assertEquals(0.0, r.results.first { it.formulaId == "gossamer-sprite" }.base.additiveValue)
    }
    @Test fun `Barbara healing is never emitted as Burst damage and C2 needs Melody Loop`() {
        val r = calculate("barbara", mapOf("constellation" to 2.0), "barbaraC2")
        assertFalse(r.results.any { it.talentKey == "burst" })
        assertEquals(0.0, r.results.first { it.formulaId == "normal-1" }.damageBonus.authoredBonus)
        val active = calculate("barbara", mapOf("constellation" to 2.0), "barbaraC2", "melodyLoop")
        assertEquals(.15, active.results.first { it.formulaId == "normal-1" }.damageBonus.authoredBonus, 1e-6)
    }
    @Test fun `Beidou power stacks scale the Skill and C6 excludes the initial Burst hit`() {
        val r = calculate("beidou", mapOf("attack" to 1000.0, "tidecallerPower" to 2.0, "constellation" to 6.0), "stormbreakerActive", "beidouC6")
        assertEquals(7948.8, r.results.first { it.formulaId == "tidecaller" }.base.value, 1e-6)
        assertEquals(0.0, r.results.first { it.formulaId == "burst-cast" }.resistanceMitigation.reduction)
        assertEquals(.15, r.results.first { it.formulaId == "lightning" }.resistanceMitigation.reduction, 1e-6)
    }
    @Test fun `Revelation C6 EM requires quest field and active Stormbreaker`() {
        val p = teams.compose(assertNotNull(service.profile("skirk")), setOf("beidou"))
        fun run(quest: Double, field: Double, burst: Boolean) = service.calculate(p, DamageCalculationRequestV2(
            mapOf("elementalMastery" to 0.0, "character.beidou.constellation" to 6.0,
                "character.beidou.witchRevelationComplete" to quest, "character.beidou.polestarFieldActive" to field),
            p.effects.associate { it.id to (it.id in setOf("character.beidou.polaris", "character.beidou.stellarConductRadiance", "character.beidou.beidouC6") || burst && it.id == "character.beidou.stormbreakerActive") }))
        assertEquals(200.0, run(1.0, 1.0, true).effectiveStats["elementalMastery"])
        assertEquals(0.0, run(0.0, 1.0, true).effectiveStats["elementalMastery"])
        assertEquals(0.0, run(1.0, 0.0, true).effectiveStats["elementalMastery"])
        assertEquals(0.0, run(1.0, 1.0, false).effectiveStats["elementalMastery"])
    }
}
