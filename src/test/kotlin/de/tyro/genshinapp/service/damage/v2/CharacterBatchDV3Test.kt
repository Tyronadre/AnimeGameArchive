package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

class CharacterBatchDV3Test {
    private val mapper = jacksonObjectMapper()
    private val service = DamageFormulaServiceV2(mapper)
    private val teams = DamageFormulaTeamServiceV2(mapper)
    private fun profile(key: String, vararg teammates: String) = teams.compose(assertNotNull(service.profile(key)), teammates.toSet())
    private fun run(p: DamageFormulaProfileV2, values: Map<String, Double> = emptyMap(), vararg effects: String) =
        service.calculate(p, DamageCalculationRequestV2(values, p.effects.associate { it.id to (it.id in effects) }))
    @Test fun `four profiles compile as owners teammates and with all constellations`() {
        for (key in listOf("chongyun", "clorinde", "collei", "cyno")) {
            service.validateCharacter(key)
            val p = profile(key)
            assertTrue(service.document(p).defaultCalculation.results.isNotEmpty())
            assertTrue(service.document(profile("nefer", key)).defaultCalculation.results.isNotEmpty())
            val r = service.calculate(p, DamageCalculationRequestV2(mapOf("constellation" to 6.0), p.effects.associate { it.id to true }))
            assertTrue(r.results.all { it.average.isFinite() })
        }
    }
    @Test fun `Chongyun infuses melee attacks and Frozen wins over Hydro while Pyro overrides Cryo`() {
        val p = profile("chongyun", "candace", "bennett")
        assertEquals("Cryo", run(p, emptyMap(), "frostInfusion").results.first { it.formulaId == "normal-1" }.element)
        assertEquals("Physical", run(p, mapOf("cryoInfusionActive" to 0.0), "frostInfusion").results.first { it.formulaId == "normal-1" }.element)
        assertEquals("Cryo", run(p, emptyMap(), "frostInfusion", "character.candace.crimsonCrown").results.first { it.formulaId == "normal-1" }.element)
        assertEquals("Pyro", run(p, mapOf("character.bennett.constellation" to 6.0), "frostInfusion", "character.bennett.fantasticVoyageField", "character.bennett.bennettC6").results.first { it.formulaId == "normal-1" }.element)
    }
    @Test fun `Chongyun Rimechaser initial hit excludes new shred and C6 bonus is conditional`() {
        val p = profile("chongyun")
        val r = run(p, mapOf("rimechaserDebuffActive" to 1.0, "enemyHpLower" to 1.0, "constellation" to 6.0), "rimechaserShred", "chongyunC6")
        assertEquals(0.0, r.results.first { it.formulaId == "rimechaser" }.resistanceMitigation.reduction)
        val blade = r.results.first { it.formulaId == "burst-blade" }
        assertEquals(.1, blade.resistanceMitigation.reduction, 1e-9)
        assertEquals(.15, blade.damageBonus.authoredBonus, 1e-9)
        assertEquals(0.0, run(p, mapOf("constellation" to 6.0), "chongyunC6").results.first { it.formulaId == "burst-blade" }.damageBonus.authoredBonus)
        val teammate = run(profile("skirk", "chongyun"), mapOf("character.chongyun.rimechaserDebuffActive" to 1.0), "character.chongyun.rimechaserShred")
        assertTrue(teammate.results.filter { it.element == "Cryo" }.all { it.resistanceMitigation.reduction == .1 })
    }
    @Test fun `Clorinde C2 replaces capped A1 and every Pact and Burst hit receives one addition`() {
        val p = profile("clorinde")
        fun calc(c2: Boolean) = run(p, mapOf("attack" to 5000.0, "constellation" to 6.0), "nightVigil", "darkShatteringFlame", *(if(c2) arrayOf("clorindeC2") else emptyArray()))
        val base = calc(false)
        val enhanced = calc(true)
        for (hit in enhanced.results.filter { it.formulaId.startsWith("impale-pact-") || it.formulaId.startsWith("burst-") }) {
            assertEquals(2700.0, hit.base.additiveValue)
            assertEquals(1800.0, base.results.first { it.formulaId == hit.formulaId }.base.additiveValue)
        }
        assertEquals(0.0, enhanced.results.first { it.formulaId == "surging-blade" }.base.additiveValue)
        assertFalse(enhanced.results.any { it.formulaId == "charged" })
        val infused = run(profile("clorinde", "bennett"), mapOf("character.bennett.constellation" to 6.0), "nightVigil", "character.bennett.fantasticVoyageField", "character.bennett.bennettC6")
        assertEquals("Electro", infused.results.first { it.formulaId == "swift-hunt-piercing" }.element)
    }
    @Test fun `Clorinde C4 uses Bond at damage and crit buffs remain independent of Night Vigil`() {
        val p = profile("clorinde")
        val r = run(p, mapOf("constellation" to 6.0, "burstBondOfLife" to 150.0, "critRate" to 5.0, "critDamage" to 50.0), "clorindeC4", "clorindeC6", "lawfulRemuneration")
        assertEquals(2.0, r.results.first { it.formulaId == "burst-a" }.damageBonus.authoredBonus)
        assertEquals(35.0, r.effectiveStats["critRate"])
        assertEquals(120.0, r.effectiveStats["critDamage"])
        assertFalse(r.results.any { it.formulaId == "c6-shade" })
    }
    @Test fun `Collei C2 grants Sprout without a reaction but requires ring return and C4 excludes herself`() {
        val p = profile("collei")
        val values = mapOf("constellation" to 6.0, "sproutReactionTriggered" to 0.0, "attack" to 1000.0, "elementalMastery" to 0.0)
        assertFalse(run(p, values, "sproutReaction", "sproutAvailable").results.any { it.formulaId == "sprout" })
        val r = run(p, values, "colleiC2", "sproutAvailable", "colleiC4", "colleiC6")
        assertEquals(400.0, r.results.first { it.formulaId == "sprout-c2" }.base.value)
        assertEquals(0.0, r.effectiveStats["elementalMastery"])
        assertFalse(p.talents.flatMap { it.formulas }.first { it.id == "c6-miniature" }.tags.any { it in setOf("elementalSkill", "elementalBurst") })
        assertFalse(run(p, values + ("floralRingReturned" to 0.0), "colleiC2", "sproutAvailable").results.any { it.formulaId.startsWith("sprout") })
        val teammate = run(profile("nefer", "collei"), mapOf("elementalMastery" to 0.0, "character.collei.constellation" to 4.0), "character.collei.colleiC4")
        assertEquals(60.0, teammate.effectiveStats["elementalMastery"])
    }
    @Test fun `Cyno Pact attacks use Burst parameters EM addition and locked Electro conversion`() {
        val p = profile("cyno", "bennett")
        val r = run(p, mapOf("attack" to 1000.0, "elementalMastery" to 300.0, "burstTalentLevel" to 1.0,
            "normalTalentLevel" to 10.0, "character.bennett.constellation" to 6.0), "pactswornPathclearer", "authorityNineBows", "character.bennett.fantasticVoyageField", "character.bennett.bennettC6")
        assertEquals(400.0, r.effectiveStats["elementalMastery"])
        val n = r.results.first { it.formulaId == "pact-normal-1" }
        assertEquals(r.effectiveStats.getValue("attack") * .782832, n.base.formulaValue, 1e-6)
        assertEquals(600.0, n.base.additiveValue)
        assertEquals("Electro", n.element)
        assertFalse(r.results.any { it.formulaId == "normal-1" })
        assertEquals(0.0, r.results.first { it.formulaId == "pact-charged" }.base.additiveValue)
    }
    @Test fun `Cyno Judication affects Mortuary Rite not bolts and Radiance swaps bolt types`() {
        val p = profile("cyno")
        val effects = arrayOf("pactswornPathclearer", "judication", "authorityNineBows", "witchRevelation", "stellarRadiance")
        val ordinary = run(p, emptyMap(), *effects)
        assertEquals(.35, ordinary.results.first { it.formulaId == "mortuary-rite" }.damageBonus.authoredBonus, 1e-9)
        assertEquals(0.0, ordinary.results.first { it.formulaId == "duststalker" }.damageBonus.authoredBonus)
        val stellar = run(p, mapOf("polestarFieldActive" to 1.0), *effects)
        assertFalse(stellar.results.any { it.formulaId == "duststalker" })
        assertTrue(stellar.results.any { it.formulaId == "starsame" })
        assertFalse(run(p, mapOf("polestarFieldActive" to 1.0, "witchRevelationComplete" to 0.0), *effects).results.any { it.formulaId == "starsame" })
    }
    @Test fun `Starsame bypasses DEF and normal bonuses with EM added after reaction scaling`() {
        val p = profile("cyno")
        val effects = arrayOf("pactswornPathclearer", "judication", "authorityNineBows", "witchRevelation", "stellarRadiance", "polestarField")
        fun hit(hits: Double, bonus: Double, enemy: Double) = run(p, mapOf("attack" to 1000.0, "elementalMastery" to 300.0,
            "polestarFieldActive" to 1.0, "polestarApplications" to hits, "elementalDamageBonus" to bonus, "enemyLevel" to enemy), *effects).results.first { it.formulaId == "starsame" }
        for ((count, factor) in listOf(0.0 to 1.0, 1.0 to 1.45, 12.0 to 2.0)) {
            val h = hit(count, 0.0, 90.0)
            assertEquals(2000.0 * factor, h.base.value, 1e-6)
            assertEquals(2400.0, h.postBonusBase.value, 1e-6)
            assertEquals((2000.0 * factor * 2.0 + 2400.0) * .9, h.nonCritical, 1e-6)
            assertTrue(h.defenseMitigation.bypassed)
            assertEquals(h.nonCritical, hit(count, 900.0, 120.0).nonCritical, 1e-6)
        }
    }
    @Test fun `Together We Rise transfers EM without current field and C2 reaches recipient Stellar damage only`() {
        val p = profile("nefer", "cyno")
        val values = mapOf("elementalMastery" to 0.0, "character.cyno.constellation" to 2.0, "character.cyno.togetherWeRiseActive" to 1.0,
            "character.cyno.stellarConductStacks" to 5.0)
        val r = run(p, values, "character.cyno.witchRevelation", "character.cyno.cynoC1", "character.cyno.togetherWeRise", "character.cyno.cynoC2")
        assertEquals(200.0, r.effectiveStats["elementalMastery"])
        val withoutC2 = run(p, values, "character.cyno.witchRevelation", "character.cyno.cynoC1", "character.cyno.togetherWeRise")
        assertEquals(withoutC2.results.map { it.damageBonus.authoredBonus }, r.results.map { it.damageBonus.authoredBonus })
        val owner = profile("cyno")
        val own = run(owner, mapOf("constellation" to 6.0, "togetherWeRiseActive" to 1.0, "stellarConductStacks" to 5.0),
            "pactswornPathclearer", "cynoC6", "cynoC1", "cynoC2", "witchRevelation", "togetherWeRise")
        assertFalse(own.results.any { it.formulaId == "c6-duststalker" })
        assertEquals(.8, own.results.first { it.formulaId == "c6-starsame" }.damageBonus.authoredBonus, 1e-9)
    }
}
