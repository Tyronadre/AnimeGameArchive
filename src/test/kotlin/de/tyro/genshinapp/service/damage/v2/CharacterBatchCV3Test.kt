package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

class CharacterBatchCV3Test {
    private val mapper = jacksonObjectMapper()
    private val service = DamageFormulaServiceV2(mapper)
    private val teams = DamageFormulaTeamServiceV2(mapper)
    private fun profile(key: String, vararg teammates: String) = teams.compose(assertNotNull(service.profile(key)), teammates.toSet())
    private fun run(p: DamageFormulaProfileV2, values: Map<String, Double> = emptyMap(), vararg effects: String) =
        service.calculate(p, DamageCalculationRequestV2(values, p.effects.associate { it.id to (it.id in effects) }))

    @Test fun `four characters validate as owners teammates and with all constellations unlocked`() {
        for (key in listOf("candace", "charlotte", "chevreuse", "chiori")) {
            service.validateCharacter(key)
            val p = profile(key)
            assertTrue(service.document(p).defaultCalculation.results.isNotEmpty())
            assertTrue(service.document(profile("nefer", key)).defaultCalculation.results.isNotEmpty())
            val r = service.calculate(p, DamageCalculationRequestV2(mapOf("constellation" to 6.0), p.effects.associate { it.id to true }))
            assertTrue(r.results.all { it.average.isFinite() })
        }
    }
    @Test fun `Candace buffs elemental Normals only and infusion toggle leaves the bonus available`() {
        val p = profile("candace")
        val r = run(p, mapOf("maxHp" to 30000.0), "crimsonCrown", "celestialDome")
        val normal = r.results.first { it.formulaId == "normal-1" }
        assertEquals("Hydro", normal.element)
        assertEquals(.35, normal.damageBonus.authoredBonus, 1e-9)
        assertEquals(0.0, r.results.first { it.formulaId == "charged" }.damageBonus.authoredBonus)
        val noInfusion = run(p, mapOf("hydroInfusionActive" to 0.0), "crimsonCrown", "celestialDome")
        assertEquals("Physical", noInfusion.results.first { it.formulaId == "normal-1" }.element)
        assertEquals(0.0, noInfusion.results.first { it.formulaId == "normal-1" }.damageBonus.authoredBonus)
        val bowProfile = profile("aloy", "candace")
        val bow = run(bowProfile, mapOf("character.candace.maxHp" to 30000.0),
            "rushingIce", "character.candace.crimsonCrown", "character.candace.celestialDome")
        val before = run(bowProfile, emptyMap(), "rushingIce")
        assertEquals(.35, bow.results.first { it.formulaId == "normal-1a-cryo" }.damageBonus.authoredBonus -
            before.results.first { it.formulaId == "normal-1a-cryo" }.damageBonus.authoredBonus, 1e-9)
    }
    @Test fun `Candace HP scaling and C6 off-field trigger are separate from talent ATK scaling`() {
        val p = profile("candace")
        val r = run(p, mapOf("maxHp" to 30000.0, "attack" to 1.0, "skillTalentLevel" to 1.0, "constellation" to 6.0,
            "c6OffFieldTrigger" to 1.0), "crimsonCrown", "candaceC6")
        assertEquals(3600.0, r.results.first { it.formulaId == "skill-press" }.base.value, 1e-6)
        assertEquals(4500.0, r.results.first { it.formulaId == "c6-wave" }.base.value, 1e-6)
        assertFalse(run(p, mapOf("constellation" to 6.0), "crimsonCrown", "candaceC6").results.any { it.formulaId == "c6-wave" })
    }
    @Test fun `Charlotte counts teammates by region not affiliation and C6 is Burst damage`() {
        val p = profile("charlotte", "furina", "chiori", "columbina")
        val r = run(p, mapOf("attack" to 1000.0, "constellation" to 6.0), "diversifiedInvestigation", "charlotteC4", "charlotteC6")
        assertEquals(10.0, r.effectiveStats["elementalDamageBonus"])
        assertEquals(1800.0, r.results.first { it.formulaId == "c6-coordinated" }.base.value, 1e-6)
        assertEquals(.1, r.results.first { it.formulaId == "c6-coordinated" }.damageBonus.authoredBonus, 1e-9)
        assertEquals(0.0, r.results.first { it.formulaId == "spiritbreath-thorn" }.damageBonus.authoredBonus)
        assertFalse(run(p, mapOf("constellation" to 6.0, "focusedEnemy" to 0.0), "charlotteC6").results.any { it.formulaId == "c6-coordinated" })
    }
    @Test fun `Chevreuse shred requires both elements and rejects any third element`() {
        fun shred(vararg members: String): Double {
            val r = run(profile("chevreuse", *members), emptyMap(), "coordinatedTactics")
            return r.results.first { it.formulaId == "skill-press" }.resistanceMitigation.reduction
        }
        assertEquals(.4, shred("beidou"), 1e-9)
        assertEquals(0.0, shred("bennett"))
        assertEquals(0.0, shred("beidou", "barbara"))
        val inactive = run(profile("chevreuse", "beidou"), mapOf("overloadedRecently" to 0.0), "coordinatedTactics")
        assertEquals(0.0, inactive.results.first { it.formulaId == "skill-press" }.resistanceMitigation.reduction)
    }
    @Test fun `Chevreuse ATK buff caps at forty percent and excludes non Pyro Electro recipients`() {
        for (hp in listOf(40000.0, 60000.0)) {
            val r = run(profile("beidou", "chevreuse", "barbara"), mapOf("attack" to 1000.0, "baseAttack" to 500.0,
                "character.barbara.attack" to 1000.0, "character.barbara.baseAttack" to 500.0,
                "character.chevreuse.maxHp" to hp), "character.chevreuse.verticalForce")
            assertEquals(1200.0, r.effectiveStats.getValue("attack"), 1e-6)
            assertEquals(1000.0, r.effectiveStats.getValue("character.barbara.attack"), 1e-6)
        }
        val r = run(profile("barbara", "chevreuse"), mapOf("constellation" to 0.0, "character.chevreuse.constellation" to 6.0,
            "character.chevreuse.c6HealingStacks" to 3.0), "character.chevreuse.chevreuseC6")
        assertEquals(60.0, r.effectiveStats["pyroDamageBonus"])
        assertEquals(60.0, r.effectiveStats["electroDamageBonus"])
    }
    @Test fun `Chiori Kinu inherits both talent scalings and C6 adds DEF only to Normals`() {
        val r = run(profile("chiori"), mapOf("attack" to 1000.0, "defense" to 2000.0, "skillTalentLevel" to 1.0,
            "constellation" to 6.0), "chioriC2", "chioriC6")
        val tamoto = r.results.first { it.formulaId == "tamoto" }.base.value
        assertEquals(2872.8, tamoto, 1e-6)
        assertEquals(tamoto * 1.7, r.results.first { it.formulaId == "c2-kinu" }.base.value, 1e-6)
        assertEquals(4700.0, r.results.first { it.formulaId == "normal-1" }.base.additiveValue, 1e-6)
        assertEquals(0.0, r.results.first { it.formulaId == "charged-a" }.base.additiveValue)
    }
    @Test fun `Chiori follow-ups are exclusive and C1 does not duplicate Finishing Touch`() {
        val p = profile("chiori", "albedo")
        val r = run(p, mapOf("constellation" to 1.0, "tailorMadeMode" to 2.0), "tailoring", "tapestry", "chioriC1", "finishingTouch")
        assertEquals("Geo", r.results.first { it.formulaId == "normal-1" }.element)
        assertFalse(r.results.any { it.formulaId == "tapestry-coordinated" })
        assertEquals(.2, r.results.first { it.formulaId == "tamoto" }.damageBonus.authoredBonus, 1e-9)
        val construct = run(p, mapOf("constellation" to 1.0, "geoConstructCreated" to 1.0), "chioriC1", "finishingTouch")
        assertEquals(.2, construct.results.first { it.formulaId == "tamoto" }.damageBonus.authoredBonus, 1e-9)
    }
    @Test fun `Candace overrides Bennett and Bennett overrides Chiori in steady state`() {
        val p = profile("chiori", "bennett", "candace")
        val effects = arrayOf("tailoring", "character.bennett.fantasticVoyageField", "character.bennett.bennettC6")
        val values = mapOf("tailorMadeMode" to 2.0, "character.bennett.constellation" to 6.0)
        assertEquals("Pyro", run(p, values, *effects).results.first { it.formulaId == "normal-1" }.element)
        assertEquals("Hydro", run(p, values, *effects, "character.candace.crimsonCrown").results.first { it.formulaId == "normal-1" }.element)
    }
}
