package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

class CharacterBatchEV3Test {
    private val mapper = jacksonObjectMapper()
    private val service = DamageFormulaServiceV2(mapper)
    private val teams = DamageFormulaTeamServiceV2(mapper)
    private fun profile(key: String, vararg teammates: String) = teams.compose(assertNotNull(service.profile(key)), teammates.toSet())
    private fun run(p: DamageFormulaProfileV2, values: Map<String, Double> = emptyMap(), vararg effects: String) =
        service.calculate(p, DamageCalculationRequestV2(values, p.effects.associate { it.id to (it.id in effects) }))

    @Test fun `four profiles validate as damage owners teammates and at C6`() {
        for (key in listOf("dahlia", "dehya", "diluc", "diona")) {
            service.validateCharacter(key)
            val p = profile(key)
            assertTrue(service.document(p).defaultCalculation.results.isNotEmpty())
            assertTrue(service.document(profile("nefer", key)).defaultCalculation.results.isNotEmpty())
            val r = service.calculate(p, DamageCalculationRequestV2(mapOf("constellation" to 6.0), p.effects.associate { it.id to true }))
            assertTrue(r.results.all { it.average.isFinite() })
        }
    }
    @Test fun `Dahlia damage scales with ATK while capped speed metrics use HP without modifying per hit damage`() {
        val p = profile("dahlia")
        val a = run(p, mapOf("attack" to 1000.0, "maxHp" to 40000.0, "skillTalentLevel" to 1.0, "constellation" to 6.0), "favonianFavor", "wellWroughtJoy", "dahliaC6")
        val b = run(p, mapOf("attack" to 1000.0, "maxHp" to 80000.0, "skillTalentLevel" to 1.0, "constellation" to 6.0), "favonianFavor", "wellWroughtJoy", "dahliaC6")
        assertEquals(2328.0, a.results.first { it.formulaId == "shower-explosion" }.base.value, 1e-6)
        assertEquals(a.results.map { it.average }, b.results.map { it.average })
        assertEquals(20.0, b.effectMetrics.getValue("wellWroughtJoy").single().value)
        assertEquals(10.0, b.effectMetrics.getValue("dahliaC6").single().value)
        val inactive = run(p, mapOf("favonianFavorActive" to 0.0, "constellation" to 6.0), "favonianFavor", "wellWroughtJoy", "dahliaC6")
        assertFalse("wellWroughtJoy" in inactive.activeEffectIds)
        assertFalse("dahliaC6" in inactive.activeEffectIds)
    }
    @Test fun `teammate enemy condition controls survive while shared enemy stats stay global`() {
        val p = profile("nefer", "diluc", "chongyun")
        assertTrue(p.inputs.any { it.id == "character.diluc.enemyHpAboveHalf" })
        assertTrue(p.inputs.any { it.id == "character.chongyun.enemyHpLower" })
        assertFalse(p.inputs.any { it.id == "character.diluc.enemyResistance" })
        val r = run(p, mapOf("character.diluc.constellation" to 6.0, "character.chongyun.constellation" to 6.0),
            "character.diluc.dilucC1", "character.chongyun.chongyunC6")
        assertTrue(r.results.all { it.average.isFinite() })
    }
    @Test fun `Dehya C1 uses effective HP in every Skill stage and Burst while C2 only buffs the next field hit`() {
        val p = profile("dehya")
        val r = run(p, mapOf("attack" to 1000.0, "maxHp" to 30000.0, "baseHp" to 10000.0, "constellation" to 6.0,
            "skillTalentLevel" to 1.0, "coordinatedAttackBoostReady" to 1.0), "dehyaC1", "dehyaC2")
        assertEquals(32000.0, r.effectiveStats["maxHp"])
        for (id in listOf("indomitable-flame", "ranging-flame", "fiery-sanctum")) {
            assertEquals(1152.0, r.results.first { it.formulaId == id }.base.additiveValue, 1e-6)
        }
        assertEquals(602.0 + 32000 * .01032, r.results.first { it.formulaId == "fiery-sanctum" }.base.formulaValue, 1e-6)
        assertEquals(1920.0, r.results.first { it.formulaId == "flame-mane-fist" }.base.additiveValue, 1e-6)
        assertEquals(.5, r.results.first { it.formulaId == "fiery-sanctum" }.damageBonus.authoredBonus)
        assertEquals(0.0, r.results.first { it.formulaId == "ranging-flame" }.damageBonus.authoredBonus)
        assertEquals(0.0, r.results.first { it.formulaId == "flame-mane-fist" }.damageBonus.authoredBonus)
    }
    @Test fun `Dehya C6 crit is Burst only and scales preceding crit stacks to four`() {
        val p = profile("dehya")
        val r = run(p, mapOf("constellation" to 6.0, "critRate" to 5.0, "critDamage" to 50.0, "c6CritStacks" to 4.0), "dehyaC6")
        val burst = r.results.first { it.formulaId == "flame-mane-fist" }
        val skill = r.results.first { it.formulaId == "fiery-sanctum" }
        assertEquals(.15, burst.crit.effectiveRate, 1e-9)
        assertEquals(.05, skill.crit.effectiveRate, 1e-9)
        val noStacks = run(p, mapOf("constellation" to 6.0, "critRate" to 5.0, "critDamage" to 50.0), "dehyaC6")
        assertTrue(burst.critical > noStacks.results.first { it.formulaId == "flame-mane-fist" }.critical)
        assertEquals(skill.critical, noStacks.results.first { it.formulaId == "fiery-sanctum" }.critical)
        val formula = p.talents.flatMap { it.formulas }.first { it.id == "flame-mane-fist" }
        assertTrue("elementalBurst" in formula.tags)
        assertFalse("normalAttack" in formula.tags)
    }
    @Test fun `Diluc infusion overrides Cryo is overridden by Hydro and applies Phoenix bonus to Dawn`() {
        val p = profile("diluc", "chongyun", "candace")
        val effects = arrayOf("dawnInfusion", "phoenixBlessing", "character.chongyun.frostInfusion")
        val r = run(p, emptyMap(), *effects)
        assertEquals("Pyro", r.results.first { it.formulaId == "normal-1" }.element)
        assertEquals(.2, r.results.first { it.formulaId == "dawn-slash" }.damageBonus.elementalBonus, 1e-9)
        val hydro = run(p, emptyMap(), "dawnInfusion", "phoenixBlessing", "character.candace.crimsonCrown")
        assertEquals("Hydro", hydro.results.first { it.formulaId == "normal-1" }.element)
        assertEquals(0.0, hydro.results.first { it.formulaId == "normal-1" }.damageBonus.elementalBonus)
        assertEquals("Physical", run(p, mapOf("pyroInfusionActive" to 0.0), "dawnInfusion").results.first { it.formulaId == "normal-1" }.element)
    }
    @Test fun `Diluc constellation bonuses target their exact hit types and C2 uses base ATK`() {
        val p = profile("diluc")
        val r = run(p, mapOf("constellation" to 6.0, "attack" to 1000.0, "baseAttack" to 500.0, "c2HitStacks" to 3.0,
            "c4RhythmReady" to 1.0, "c6NormalBuffReady" to 1.0), "dilucC1", "dilucC2", "dilucC4", "dilucC6")
        assertEquals(1150.0, r.effectiveStats["attack"])
        assertEquals(.15, r.results.first { it.formulaId == "onslaught-1" }.damageBonus.authoredBonus, 1e-9)
        assertEquals(.55, r.results.first { it.formulaId == "onslaught-2" }.damageBonus.authoredBonus, 1e-9)
        assertEquals(.45, r.results.first { it.formulaId == "normal-1" }.damageBonus.authoredBonus, 1e-9)
        assertEquals(.15, r.results.first { it.formulaId == "charged-loop" }.damageBonus.authoredBonus, 1e-9)
        assertEquals(0.0, run(p, mapOf("constellation" to 6.0, "enemyHpAboveHalf" to 0.0), "dilucC1").results.first().damageBonus.authoredBonus)
    }
    @Test fun `Diona extra paws require quest and trigger while C2 buffs each paw and not Burst`() {
        val p = profile("diona")
        val values = mapOf("constellation" to 6.0, "attack" to 1000.0, "skillTalentLevel" to 1.0, "revelationPawsReady" to 1.0)
        val r = run(p, values, "witchRevelation", "choiceTreasures", "dionaC2")
        assertEquals(419.2, r.results.first { it.formulaId == "revelation-paw" }.base.value, 1e-6)
        assertEquals(.15, r.results.first { it.formulaId == "revelation-paw" }.damageBonus.authoredBonus, 1e-9)
        assertEquals(0.0, r.results.first { it.formulaId == "burst-cast" }.damageBonus.authoredBonus)
        assertFalse(run(p, values + ("witchRevelationComplete" to 0.0), "witchRevelation", "choiceTreasures").results.any { it.formulaId == "revelation-paw" })
        assertFalse(run(p, values + ("revelationPawsReady" to 0.0), "witchRevelation", "choiceTreasures").results.any { it.formulaId == "revelation-paw" })
    }
    @Test fun `Diona C6 HP threshold recipient scope and EM linger are independent of permanent Revelation HP`() {
        val p = profile("nefer", "diona")
        val effects = arrayOf("character.diona.witchRevelation", "character.diona.signatureMixRecipient", "character.diona.dionaC6")
        val values = mapOf("elementalMastery" to 0.0, "character.diona.constellation" to 6.0,
            "character.diona.maxHp" to 20000.0, "character.diona.baseHp" to 10000.0)
        val high = run(p, values + ("character.diona.recipientHpPercent" to 51.0), *effects)
        assertEquals(200.0, high.effectiveStats["elementalMastery"])
        assertEquals(22500.0, high.effectiveStats["character.diona.maxHp"])
        val low = run(p, values + ("character.diona.recipientHpPercent" to 50.0), *effects)
        assertEquals(0.0, low.effectiveStats["elementalMastery"])
        assertEquals(30.0, low.effectMetrics.getValue("character.diona.dionaC6").single().value)
        assertEquals(200.0, run(p, values + ("character.diona.recipientInsideField" to 0.0), *effects).effectiveStats["elementalMastery"])
        val noQuest = run(p, values + ("character.diona.witchRevelationComplete" to 0.0), *effects)
        assertEquals(20000.0, noQuest.effectiveStats["character.diona.maxHp"])
        assertEquals(200.0, noQuest.effectiveStats["elementalMastery"])
    }
    @Test fun `Diona Conduct C6 buffs Starsame below half HP but not ordinary damage or other Radiance states`() {
        val p = profile("cyno", "diona")
        val effects = arrayOf("pactswornPathclearer", "judication", "witchRevelation", "stellarRadiance",
            "character.diona.witchRevelation", "character.diona.radianceConduct", "character.diona.radianceSwirl", "character.diona.signatureMixRecipient", "character.diona.dionaC6")
        val values = mapOf("polestarFieldActive" to 1.0, "character.diona.constellation" to 6.0,
            "character.diona.radianceState" to 1.0, "character.diona.recipientHpPercent" to 50.0)
        val r = run(p, values, *effects)
        assertEquals(.4, r.results.first { it.formulaId == "starsame" }.damageBonus.authoredBonus, 1e-9)
        assertEquals(.35, r.results.first { it.formulaId == "mortuary-rite" }.damageBonus.authoredBonus, 1e-9)
        for (overrides in listOf(mapOf("character.diona.radianceState" to 2.0), mapOf("character.diona.recipientInsideField" to 0.0),
            mapOf("character.diona.witchRevelationComplete" to 0.0))) {
            assertEquals(0.0, run(p, values + overrides, *effects).results.first { it.formulaId == "starsame" }.damageBonus.authoredBonus)
        }
    }
    @Test fun `Diona Radiance reaction bonuses select Superconduct and Cryo Swirl only`() {
        val p = profile("diona", "cyno")
        val effects = arrayOf("witchRevelation", "radianceConduct", "radianceSwirl", "signatureMixRecipient", "dionaC6")
        val values = mapOf("constellation" to 6.0, "radianceState" to 1.0)
        val r = run(p, values, *effects).results.first { it.formulaId == "fully-charged" }
        assertEquals(.4, r.reactions.first { it.id == "superconduct" }.reactionDamageBonus, 1e-9)
        // Synthetic Anemo hit exercises the portable Cryo-Swirl selector before an Anemo owner is authored.
        val swirlProfile = p.copy(damageTypes = p.damageTypes.map { if(it.id == "directCryo") it.copy(element = "Anemo") else it })
        val swirl = run(swirlProfile, values + ("radianceState" to 2.0), *effects).results.first { it.formulaId == "fully-charged" }
        assertEquals(.4, swirl.reactions.first { it.key == "swirl-cryo" }.reactionDamageBonus, 1e-9)
        assertEquals(0.0, swirl.reactions.first { it.key == "swirl-electro" }.reactionDamageBonus)
    }
}
