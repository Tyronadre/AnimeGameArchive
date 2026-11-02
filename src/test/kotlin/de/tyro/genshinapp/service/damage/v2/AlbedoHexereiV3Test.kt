package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.mockito.Mockito.*
import kotlin.test.*

class AlbedoHexereiV3Test {
    private val mapper = jacksonObjectMapper()
    private val service = DamageFormulaServiceV2(mapper)
    private val roster = mock(DamageTeamRosterService::class.java).also {
        `when`(it.members()).thenReturn(listOf(DamageTeamMemberV2("fischl", "Fischl", "Electro", 0, weaponType = "Bow")))
    }
    private val teams = DamageFormulaTeamServiceV2(mapper, roster)
    private fun profile(owner: String = "albedo") = teams.compose(assertNotNull(service.profile(owner)),
        if (owner == "albedo") setOf("fischl") else setOf("albedo", "fischl"))
    private fun id(p: DamageFormulaProfileV2, name: String) = if (p.characterKey == "albedo") name else "character.albedo.$name"
    private fun calculate(p: DamageFormulaProfileV2, values: Map<String, Double> = emptyMap(), vararg enabled: String): DamageCalculationV2 {
        val bindings = if (p.characterKey == "albedo") p.statBindings else p.supportProfiles.first { it.key == "albedo" }.statBindings
        return service.calculate(p, DamageCalculationRequestV2(values.mapKeys { (key, _) -> bindings[key] ?: id(p, key) },
            p.effects.associate { it.id to (it.id in enabled.map { name -> id(p, name) }) }))
    }
    @Test fun `Secret Rite needs two eligible members and completed Homework`() {
        val solo = teams.compose(assertNotNull(service.profile("albedo")), emptySet())
        assertEquals(1.0, solo.teamContext["hexereiCount"])
        assertFalse("albedoHexereiRite" in calculate(solo, emptyMap(), "albedoHexereiRite").activeEffectIds)
        val p = profile()
        assertEquals(2.0, p.teamContext["hexereiCount"])
        assertTrue("albedoHexereiRite" in calculate(p, emptyMap(), "albedoHexereiRite").activeEffectIds)
        assertFalse("albedoHexereiRite" in calculate(p, mapOf("witchHomeworkComplete" to 0.0), "albedoHexereiRite").activeEffectIds)
    }
    @Test fun `Silver presence adds DEF scaling independently of low enemy HP`() {
        val p = profile()
        for (count in listOf(1.0, 2.0)) {
            val result = calculate(p, mapOf("defense" to 2000.0, "silverIsotomaCount" to count), "albedoHexereiRite", "albedoSilverIsotoma")
            assertEquals(4800.0, result.results.first { it.formulaId == "transient-blossom" }.base.additiveValue, 1e-6)
        }
        val absent = calculate(p, mapOf("silverIsotomaCount" to 0.0), "albedoHexereiRite", "albedoSilverIsotoma")
        assertEquals(0.0, absent.results.first { it.formulaId == "transient-blossom" }.base.additiveValue)
    }
    @Test fun `C1 uses base DEF and only applies with Secret Rite`() {
        val p = profile()
        val values = mapOf("constellation" to 1.0, "defense" to 2000.0, "baseDefense" to 800.0)
        assertEquals(2400.0, calculate(p, values, "albedoC1", "albedoHexereiRite").effectiveStats["defense"])
        assertEquals(2000.0, calculate(p, values, "albedoC1").effectiveStats["defense"])
    }
    @Test fun `creation bonuses cap separately and restrict Silver bonus to Hexerei recipients`() {
        val own = calculate(profile(), mapOf("defense" to 5000.0), "albedoHexereiRite", "solarRiteBuff", "silverRiteBuff")
        assertEquals(.42, own.results.first { it.formulaId == "skill-cast" }.damageBonus.authoredBonus, 1e-6)
        val other = calculate(profile("nefer"), mapOf("defense" to 5000.0), "albedoHexereiRite", "solarRiteBuff", "silverRiteBuff")
        assertEquals(.12, other.results.first { it.formulaId == "skill-hit" }.damageBonus.authoredBonus, 1e-6)
    }
    @Test fun `C4 Silver jump affects ground impact only and can outlast the destroyed construct`() {
        val result = calculate(profile(), mapOf("constellation" to 4.0, "silverPlungeBuffActive" to 1.0, "silverIsotomaCount" to 0.0),
            "albedoHexereiRite", "albedoC4", "albedoSilverPlunge")
        assertEquals(.3, result.results.first { it.formulaId == "plunge" }.damageBonus.authoredBonus, 1e-6)
        assertEquals(.6, result.results.first { it.formulaId == "plunge-low" }.damageBonus.authoredBonus, 1e-6)
    }
    @Test fun `C2 off field proc is a separate DEF hit without adding the cast stack bonus`() {
        val result = calculate(profile(), mapOf("constellation" to 2.0, "defense" to 2000.0, "fatalReckoningStacks" to 4.0, "c2OffFieldProcAvailable" to 1.0),
            "albedoHexereiRite", "albedoC2", "albedoC2OffFieldTrigger")
        assertEquals(6000.0, result.results.first { it.formulaId == "c2-fatal-blossom" }.base.value, 1e-6)
    }
    @Test fun `C6 forces four cast stacks and adds its buff only to Fatal Blossoms`() {
        val result = calculate(profile(), mapOf("constellation" to 6.0, "defense" to 2000.0, "fatalReckoningStacks" to 0.0,
            "silverDestroyedByBurst" to 1.0, "c6FatalBlossomBuffActive" to 1.0, "silverIsotomaCount" to 0.0),
            "albedoHexereiRite", "albedoC2", "albedoC6", "albedoSilverBurstStacks", "albedoC6FatalBonus")
        assertEquals(4.0, result.effectiveStats["fatalReckoningStacks"])
        assertEquals(2400.0, result.results.first { it.formulaId == "burst-hit" }.base.additiveValue, 1e-6)
        assertEquals(7400.0, result.results.first { it.formulaId == "fatal-blossom" }.base.additiveValue, 1e-6)
    }
}
