package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

class CharacterBatchFV3Test {
    private val mapper = jacksonObjectMapper()
    private val service = DamageFormulaServiceV2(mapper)
    private val teams = DamageFormulaTeamServiceV2(mapper)
    private fun profile(key: String, vararg teammates: String) = teams.compose(assertNotNull(service.profile(key)), teammates.toSet())
    private fun run(p: DamageFormulaProfileV2, values: Map<String, Double> = emptyMap(), vararg effects: String) =
        service.calculate(p, DamageCalculationRequestV2(values, p.effects.associate { it.id to (it.id in effects) }))

    @Test fun `four profiles compile as owners teammates and all constellations`() {
        for (key in listOf("dori", "durin", "emilie", "eula")) {
            service.validateCharacter(key)
            val p = profile(key)
            assertTrue(service.document(p).defaultCalculation.results.isNotEmpty())
            assertTrue(service.document(profile("nefer", key)).defaultCalculation.results.isNotEmpty())
            val r = service.calculate(p, DamageCalculationRequestV2(mapOf("constellation" to 6.0), p.effects.associate { it.id to true }))
            assertTrue(r.results.all { it.average.isFinite() })
        }
    }
    @Test fun `Dori connector is Burst while C2 is an untyped ATK proc and C6 infusion is overrideable`() {
        val p = profile("dori", "bennett")
        val r = run(p, mapOf("constellation" to 6.0, "attack" to 1000.0, "c6InfusionActive" to 1.0), "jinniConnection", "doriC2", "doriC6")
        assertEquals(500.0, r.results.first { it.formulaId == "jinni-toop" }.base.value)
        assertFalse(p.talents.flatMap { it.formulas }.first { it.id == "jinni-toop" }.tags.any { it in setOf("elementalSkill", "elementalBurst") })
        assertEquals("Electro", r.results.first { it.formulaId == "normal-1" }.element)
        val overridden = run(p, mapOf("constellation" to 6.0, "c6InfusionActive" to 1.0, "character.bennett.constellation" to 6.0),
            "doriC6", "character.bennett.fantasticVoyageField", "character.bennett.bennettC6")
        assertEquals("Pyro", overridden.results.first { it.formulaId == "normal-1" }.element)
        assertFalse(run(p, mapOf("constellation" to 2.0, "jinniConnected" to 0.0), "jinniConnection", "doriC2").results.any { it.formulaId == "jinni-toop" })
    }
    @Test fun `Dori ER restoration is continuous and C4 thresholds are strictly below half for active recipient`() {
        val p = profile("dori")
        for ((er, expected) in listOf(150.0 to 7.5, 300.0 to 15.0, 500.0 to 15.0)) {
            assertEquals(expected, run(p, mapOf("energyRecharge" to er), "compoundInterest").effectMetrics.getValue("compoundInterest").single().value)
        }
        val team = profile("nefer", "dori")
        val values = mapOf("energyRecharge" to 100.0, "character.dori.constellation" to 4.0, "character.dori.recipientEnergyPercent" to 49.0,
            "character.dori.recipientHpPercent" to 49.0)
        val r = run(team, values, "character.dori.jinniConnection", "character.dori.doriC4")
        assertEquals(130.0, r.effectiveStats["energyRecharge"])
        assertEquals(50.0, r.effectMetrics.getValue("character.dori.doriC4").single().value)
        val half = run(team, values + mapOf("character.dori.recipientEnergyPercent" to 50.0, "character.dori.recipientHpPercent" to 50.0),
            "character.dori.jinniConnection", "character.dori.doriC4")
        assertEquals(100.0, half.effectiveStats["energyRecharge"])
        assertEquals(0.0, half.effectMetrics.getValue("character.dori.doriC4").single().value)
    }
    @Test fun `Durin forms are exclusive and Hexerei upgrades shred only with quest and another eligible member`() {
        val p = profile("durin", "albedo")
        val effects = arrayOf("burstWindow", "whiteForm", "darkForm", "secretRite", "whiteCalculus", "darkCalculus")
        val v = mapOf("overloadedTriggerActive" to 1.0)
        val r = run(p, v, *effects)
        assertTrue(r.results.any { it.formulaId == "white-dragon" })
        assertFalse(r.results.any { it.formulaId == "dark-dragon" })
        assertEquals(.35, r.results.first { it.formulaId == "white-dragon" }.resistanceMitigation.reduction, 1e-9)
        assertEquals(.2, run(p, v + ("witchHomeworkComplete" to 0.0), *effects).results.first { it.formulaId == "white-dragon" }.resistanceMitigation.reduction, 1e-9)
        assertEquals(.2, run(profile("durin"), v, *effects).results.first { it.formulaId == "white-dragon" }.resistanceMitigation.reduction, 1e-9)
        val dark = run(p, v + ("burstForm" to 1.0), *effects)
        assertFalse(dark.results.any { it.formulaId == "white-dragon" })
        assertEquals(0.0, dark.results.first { it.formulaId == "dark-dragon" }.resistanceMitigation.reduction)
    }
    @Test fun `Durin Dark reaction bonus is seventy with Rite and forty without`() {
        val p = profile("durin", "albedo", "barbara")
        val effects = arrayOf("burstWindow", "darkForm", "darkCalculus", "secretRite")
        fun bonus(quest: Double) = run(p, mapOf("burstForm" to 1.0, "witchHomeworkComplete" to quest), *effects)
            .results.first { it.formulaId == "dark-dragon" }.reactions.first { it.id == "vaporize" }.reactionDamageBonus
        assertEquals(.7, bonus(1.0), 1e-9)
        assertEquals(.4, bonus(0.0), 1e-9)
    }
    @Test fun `Durin A4 multiplies only periodic talent base and not C1 flat addition`() {
        val p = profile("durin")
        val r = run(p, mapOf("attack" to 2500.0, "constellation" to 1.0, "burstForm" to 1.0), "burstWindow", "darkForm", "primordialFusion", "durinC1")
        val periodic = r.results.first { it.formulaId == "dark-dragon" }
        assertEquals(1.75, periodic.base.multiplier, 1e-9)
        assertEquals(3750.0, periodic.base.additiveValue)
        assertEquals(periodic.base.formulaValue * 1.75 + 3750.0, periodic.base.value, 1e-6)
        assertEquals(1.0, r.results.first { it.formulaId == "dark-cast-1" }.base.multiplier)
        val depleted = run(p, mapOf("constellation" to 1.0, "burstForm" to 1.0, "cycleStacksRemaining" to 1.0), "burstWindow", "darkForm", "durinC1")
        assertEquals(0.0, depleted.results.first { it.formulaId == "dark-dragon" }.base.additiveValue)
    }
    @Test fun `Durin White C1 excludes himself and Lunar hits while C2 buffs each matching element once`() {
        val p = profile("albedo", "durin")
        val effects = arrayOf("character.durin.burstWindow", "character.durin.whiteForm", "character.durin.durinC1", "character.durin.durinC2")
        val r = run(p, mapOf("character.durin.constellation" to 2.0, "character.durin.attack" to 2000.0,
            "character.durin.crystallizeTriggerActive" to 1.0, "character.durin.overloadedTriggerActive" to 1.0), *effects)
        assertEquals(1200.0, r.results.first { it.formulaId == "skill-cast" }.base.additiveValue)
        assertEquals(50.0, r.effectiveStats["elementalDamageBonus"])
        assertEquals(50.0, r.effectiveStats["pyroDamageBonus"])
        val own = run(profile("durin"), mapOf("constellation" to 1.0), "burstWindow", "whiteForm", "durinC1")
        assertEquals(0.0, own.results.first { it.formulaId == "white-dragon" }.base.additiveValue)
        val lunar = run(profile("nefer", "durin"), mapOf("character.durin.constellation" to 1.0), "character.durin.burstWindow", "character.durin.whiteForm", "character.durin.durinC1")
        assertTrue(lunar.results.filter { it.damageType == "lunarBloom" }.all { it.base.additiveValue == 0.0 })
    }
    @Test fun `Durin C6 separates personal DEF ignore from enemy DEF reduction`() {
        val p = profile("durin")
        val r = run(p, mapOf("constellation" to 6.0, "burstForm" to 1.0, "whiteDefenseDebuffActive" to 1.0), "burstWindow", "darkForm", "durinC6")
        assertEquals(.7, r.results.first { it.formulaId == "dark-dragon" }.defenseMitigation.ignore, 1e-9)
        assertEquals(.3, r.results.first { it.formulaId == "dark-dragon" }.defenseMitigation.reduction, 1e-9)
        assertEquals(0.0, r.results.first { it.formulaId == "confirmation-purity" }.defenseMitigation.ignore)
        val t = run(profile("albedo", "durin"), mapOf("character.durin.constellation" to 6.0, "character.durin.whiteDefenseDebuffActive" to 1.0), "character.durin.durinC6")
        assertEquals(.3, t.results.first().defenseMitigation.reduction, 1e-9)
        assertEquals(0.0, t.results.first().defenseMitigation.ignore)
    }
    @Test fun `Emilie Burning bonus caps and untyped Cleardew receives C1 but not Burst bonus`() {
        val p = profile("emilie")
        val r = run(p, mapOf("attack" to 3000.0, "constellation" to 1.0), "caseLevelTwo", "lingeringFragrance", "rectification", "emilieC1")
        assertEquals(18000.0, r.results.first { it.formulaId == "cleardew" }.base.value)
        assertEquals(.56, r.results.first { it.formulaId == "cleardew" }.damageBonus.authoredBonus, 1e-9)
        assertEquals(.36, r.results.first { it.formulaId == "scented-dew" }.damageBonus.authoredBonus, 1e-9)
        assertEquals(.36, r.results.first { it.formulaId == "spiritbreath-thorn" }.damageBonus.authoredBonus, 1e-9)
        assertFalse(p.talents.flatMap { it.formulas }.first { it.id == "cleardew" }.tags.contains("elementalSkill"))
        val noBurn = run(p, mapOf("enemyBurning" to 0.0), "rectification")
        assertEquals(0.0, noBurn.results.first().damageBonus.authoredBonus)
        val levelOne = run(p, mapOf("caseLevel" to 1.0), "caseLevelOne", "caseLevelTwo", "lingeringFragrance")
        assertTrue(levelOne.results.any { it.formulaId == "level-1-puff" })
        assertFalse(levelOne.results.any { it.formulaId == "level-2-puff-a" || it.formulaId == "cleardew" })
    }
    @Test fun `Emilie C6 adds ATK only to protected converted Normals and Charged attacks`() {
        val p = profile("emilie", "bennett")
        val r = run(p, mapOf("attack" to 1000.0, "constellation" to 6.0, "abidingFragranceActive" to 1.0,
            "character.bennett.constellation" to 6.0), "emilieC6", "character.bennett.fantasticVoyageField", "character.bennett.bennettC6")
        val hit = r.results.first { it.formulaId == "normal-1-dendro" }
        assertEquals("Dendro", hit.element)
        assertEquals(r.effectiveStats.getValue("attack") * 3, hit.base.additiveValue, 1e-6)
        assertFalse(r.results.any { it.formulaId == "normal-1" })
        assertEquals("Pyro", r.results.first { it.formulaId == "plunge-low" }.element)
        assertEquals(0.0, r.results.first { it.formulaId == "plunge-low" }.base.additiveValue)
        val debuff = run(profile("nefer", "emilie"), mapOf("character.emilie.constellation" to 2.0, "character.emilie.dendroShredActive" to 1.0), "character.emilie.emilieC2")
        assertTrue(debuff.results.filter { it.element == "Dendro" }.all { it.resistanceMitigation.reduction == .3 })
    }
    @Test fun `Eula Lightfall uses Burst stacks capped at thirty and C6 floors inclusive total at five`() {
        val p = profile("eula")
        val v = mapOf("attack" to 1000.0, "burstTalentLevel" to 1.0, "constellation" to 6.0)
        fun hit(stacks: Double, c6: Boolean) = run(p, v + ("lightfallStacks" to stacks), *(if(c6) arrayOf("eulaC6") else emptyArray())).results.first { it.formulaId == "lightfall-explosion" }.base.value
        assertEquals(3670.48, hit(0.0, false), 1e-6)
        assertEquals(3670.48 + 5 * 749.92, hit(0.0, true), 1e-6)
        assertEquals(hit(5.0, false), hit(5.0, true), 1e-6)
        assertEquals(hit(30.0, true), hit(100.0, true), 1e-6)
    }
    @Test fun `Eula two-stack Shattered Sword scales with half Burst base and C4 excludes Cryo cast`() {
        val p = profile("eula")
        val r = run(p, mapOf("attack" to 1000.0, "burstTalentLevel" to 1.0, "grimheartConsumed" to 2.0,
            "lightfallStacks" to 30.0, "constellation" to 4.0, "enemyBelowHalf" to 1.0), "icewhirlAvailable", "roilingRime", "eulaC4")
        assertEquals(1835.24, r.results.first { it.formulaId == "shattered-lightfall" }.base.value, 1e-6)
        assertEquals(.25, r.results.first { it.formulaId == "shattered-lightfall" }.damageBonus.authoredBonus)
        assertEquals(0.0, r.results.first { it.formulaId == "burst-cast" }.damageBonus.authoredBonus)
        assertFalse(run(p, mapOf("grimheartConsumed" to 1.0), "roilingRime").results.any { it.formulaId == "shattered-lightfall" })
        val t = run(profile("albedo", "eula"), mapOf("character.eula.resistanceDebuffActive" to 1.0, "character.eula.skillTalentLevel" to 1.0), "character.eula.icetideDebuff")
        assertEquals(.16, t.results.first { it.formulaId == "normal-1" }.resistanceMitigation.reduction, 1e-9)
        val defense = run(p, mapOf("defense" to 1000.0, "baseDefense" to 500.0, "grimheartStacks" to 2.0), "grimheartDefense")
        assertEquals(1300.0, defense.effectiveStats["defense"])
    }
}
