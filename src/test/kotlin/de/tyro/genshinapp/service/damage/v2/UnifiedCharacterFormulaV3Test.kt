package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

class UnifiedCharacterFormulaV3Test {
    private val mapper = jacksonObjectMapper()
    private val registry = CharacterFormulaRegistryV3(mapper)
    private val service = DamageFormulaServiceV2(mapper)
    private val teams = DamageFormulaTeamServiceV2(mapper)
    private fun party(owner: String, vararg others: String) = teams.compose(assertNotNull(service.profile(owner)), others.toSet())
    private fun effectId(profile: DamageFormulaProfileV2, owner: String, local: String) =
        if (profile.characterKey == owner) local else "character.$owner.$local"
    private fun stat(profile: DamageFormulaProfileV2, owner: String, name: String): String =
        if (profile.characterKey == owner) profile.statBindings.getValue(name)
        else profile.supportProfiles.first { it.key == owner }.statBindings.getValue(name)
    private fun calculate(profile: DamageFormulaProfileV2, values: Map<String, Double>, vararg effects: String) = service.calculate(profile,
        DamageCalculationRequestV2(values, profile.effects.associate { it.id to (it.id in effects) }))

    @Test fun `one registry owns all characters and partial kits do not claim a damage calculator`() {
        assertEquals(37, registry.definitions.size)
        assertTrue(registry.definitions.values.all { it.schemaVersion == 3 })
        for (key in listOf("lauma", "nahida", "columbina")) {
            assertTrue(service.supports(key))
            assertNotNull(service.profile(key))
            service.validateCharacter(key)
            assertTrue(party("nefer", key).teamMembers.first { it.key == key }.calculatorAvailable)
        }
        assertTrue(service.supports("bennett"))
        assertTrue(DamageFormulaResourcesV2.resources("classpath*:data/damage-formulas-v2/teammates/*.json").isEmpty())
    }

    @Test fun `Bennett field uses his base ATK in both roles and only buffs the active recipient`() {
        for (owner in listOf("bennett", "arlecchino")) {
            val p = party(owner, if (owner == "bennett") "arlecchino" else "bennett")
            val values = mutableMapOf(stat(p, owner, "attack") to 1000.0,
                stat(p, "bennett", "baseAttack") to 800.0,
                p.characterProgressInputs.getValue("bennett").getValue("burstTalentLevel") to 10.0)
            if (owner != "bennett") values[stat(p, "bennett", "attack")] = 1500.0
            val r = calculate(p, values, effectId(p, "bennett", "fantasticVoyageField"), effectId(p, "bennett", "bennettC1"))
            assertEquals(1000.0 + 800.0 * (1.008 + .2), r.effectiveStats.getValue(stat(p, owner, "attack")), 1e-6)
            if (owner != "bennett") assertEquals(1500.0, r.effectiveStats.getValue(stat(p, "bennett", "attack")))
        }
    }

    @Test fun `Bennett constellation buffs cannot outlive the field dependency`() {
        val p = party("arlecchino", "bennett")
        val r = calculate(p, mapOf("attack" to 1000.0), effectId(p, "bennett", "bennettC1"), effectId(p, "bennett", "bennettC6"))
        assertEquals(1000.0, r.effectiveStats.getValue("attack"))
        assertFalse(effectId(p, "bennett", "bennettC1") in r.activeEffectIds)
        assertFalse(effectId(p, "bennett", "bennettC6") in r.activeEffectIds)
    }

    @Test fun `Citlali C2 resolves self recipient and enemy components independently`() {
        val p = party("furina", "citlali")
        val r = calculate(p, mapOf(stat(p, "furina", "elementalMastery") to 100.0,
            stat(p, "citlali", "elementalMastery") to 100.0), effectId(p, "citlali", "citlaliC2"))
        assertEquals(350.0, r.effectiveStats.getValue(stat(p, "furina", "elementalMastery")))
        assertEquals(225.0, r.effectiveStats.getValue(stat(p, "citlali", "elementalMastery")))
        assertEquals(.2, r.results.first { it.formulaId == "usher" }.resistanceMitigation.reduction, 1e-9)
        val own = party("citlali", "furina")
        assertEquals(225.0, calculate(own, mapOf("elementalMastery" to 100.0), "citlaliC2").effectiveStats["elementalMastery"])
    }

    @Test fun `Skirk skill level bonus reaches every member from either selected role`() {
        for (owner in listOf("skirk", "furina")) {
            val p = party(owner, *(setOf("skirk", "furina", "escoffier", "citlali") - owner).toTypedArray())
            val levels = p.characterProgressInputs.values.associate { it.getValue("skillTalentLevel") to 10.0 }
            val r = calculate(p, levels, effectId(p, "skirk", "mutualWeaponsMentorship"))
            levels.keys.forEach { assertEquals(11.0, r.effectiveStats.getValue(it)) }
        }
    }

    @Test fun `personal support stat changes remain active without leaking into the damage owner`() {
        val p = party("skirk", "furina")
        val r = calculate(p, mapOf(stat(p, "skirk", "maxHp") to 20000.0,
            stat(p, "furina", "maxHp") to 30000.0, stat(p, "furina", "baseHp") to 10000.0,
            "character.furina.fanfareAboveLimit" to 400.0), effectId(p, "furina", "furinaC2"))
        assertEquals(44000.0, r.effectiveStats.getValue(stat(p, "furina", "maxHp")), 1e-6)
        assertEquals(20000.0, r.effectiveStats.getValue(stat(p, "skirk", "maxHp")))
    }

    @Test fun `source EM bonuses precede active character sharing without recursive conversion`() {
        val p = party("nefer", "nahida")
        val r = calculate(p, mapOf("elementalMastery" to 100.0, stat(p, "nahida", "elementalMastery") to 900.0,
            "character.nahida.nahidaMarkedOpponents" to 3.0), effectId(p, "nahida", "nahidaC4"), effectId(p, "nahida", "nahidaCompassionIlluminated"))
        assertEquals(1040.0, r.effectiveStats.getValue(stat(p, "nahida", "elementalMastery")))
        assertEquals(350.0, r.effectiveStats.getValue("elementalMastery"))
    }

    @Test fun `personal hit modifiers do not target another characters same named hits`() {
        val p = party("skirk", "arlecchino")
        val inactive = calculate(p, emptyMap())
        val enabled = calculate(p, emptyMap(), effectId(p, "arlecchino", "masqueRedDeath"), effectId(p, "arlecchino", "arlecchinoC1"))
        assertEquals(inactive.results, enabled.results)
        assertTrue(p.effects.filter { it.sourceCharacterKey == "arlecchino" }.all { it.formulaModifiers.isEmpty() })
    }

    @Test fun `recipient ATK percentages use the recipients base not the source base`() {
        val p = party("arlecchino", "xiangling")
        val r = calculate(p, mapOf("attack" to 1000.0, "baseAttack" to 1000.0,
            stat(p, "xiangling", "baseAttack") to 2000.0), effectId(p, "xiangling", "bewareSuperHot"))
        assertEquals(1100.0, r.effectiveStats.getValue("attack"))
    }

    @Test fun `removed characters cannot contribute through stale inputs or toggles`() {
        val p = party("skirk")
        val baseline = calculate(p, emptyMap())
        val stale = service.calculate(p, DamageCalculationRequestV2(inputs = mapOf("character.furina.fanfarePoints" to 400.0),
            enabledEffects = p.effects.associate { it.id to false } + ("character.furina.universalRevelry" to true)))
        assertEquals(baseline, stale)
    }
}
