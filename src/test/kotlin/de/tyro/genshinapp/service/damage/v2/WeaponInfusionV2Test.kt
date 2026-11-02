package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

class WeaponInfusionV2Test {
    private val mapper = jacksonObjectMapper()
    private val service = DamageFormulaServiceV2(mapper)
    private val teams = DamageFormulaTeamServiceV2(mapper)
    private fun party(owner: String) = teams.compose(assertNotNull(service.profile(owner)), if (owner == "bennett") emptySet() else setOf("bennett"))
    private fun effect(owner: String, local: String) = if (owner == "bennett") local else "character.bennett.$local"
    private fun calculate(p: DamageFormulaProfileV2, values: Map<String, Double> = emptyMap(), field: Boolean = true, c6: Boolean = true, vararg other: String): DamageCalculationV2 =
        service.calculate(p, DamageCalculationRequestV2(values, p.effects.associate { e -> e.id to
            (field && e.id == effect(p.key, "fantasticVoyageField") || c6 && e.id == effect(p.key, "bennettC6") || e.id in other) }))
    @Test fun `Bennett infuses all eligible weapon classes without duplicate attacks`() {
        for (owner in listOf("bennett", "albedo", "beidou", "xiangling")) {
            val p = party(owner)
            val r = calculate(p)
            val normals = p.talents.flatMap { it.formulas }.filter { f -> f.damageType == "directPhysical" && f.tags.any { it in setOf("normalAttack", "chargedAttack", "plungingAttack") } }
            normals.forEach { formula ->
                val hit = r.results.first { it.formulaId == formula.id }
                assertEquals("directPyro", hit.damageType, "$owner ${formula.id}")
                assertEquals(effect(owner, "bennettC6"), hit.infusionSourceEffectId)
            }
            assertEquals(r.results.size, r.results.map { it.formulaId }.distinct().size)
            assertFalse(p.talents.flatMap { it.formulas }.any { it.id.startsWith("c6-normal") })
        }
    }
    @Test fun `infusion uses Pyro bonus and resistance rather than Physical or native element stats`() {
        val p = party("albedo")
        val doc = service.document(p)
        assertTrue(doc.profile.damageTypes.any { it.id == "directPyro" && it.resistanceInput == "enemyPyroResistance" })
        val r = calculate(p, mapOf("attack" to 1000.0, "physicalDamageBonus" to 300.0,
            "elementalDamageBonus" to 500.0, "pyroDamageBonus" to 50.0, "enemyPhysicalResistance" to 90.0,
            "enemyResistance" to 90.0, "enemyPyroResistance" to 10.0))
        val hit = r.results.first { it.formulaId == "normal-1" }
        assertEquals(.65, hit.damageBonus.elementalBonus, 1e-9)
        assertEquals(1.65, hit.damageBonus.multiplier, 1e-9)
        assertEquals(.9, hit.resistanceMitigation.multiplier, 1e-9)
        assertEquals("directGeo", r.results.first { it.formulaId == "skill-cast" }.damageType)
    }
    @Test fun `field dependency removes infusion and bonus while ordinary attacks retain their IDs`() {
        val p = party("albedo")
        for (r in listOf(calculate(p, field = false), calculate(p, c6 = false))) {
            assertEquals("directPhysical", r.results.first { it.formulaId == "normal-1" }.damageType)
            assertNull(r.results.first { it.formulaId == "normal-1" }.infusionSourceEffectId)
        }
    }
    @Test fun `Bows and catalysts are not infused while all weapons retain the Pyro bonus`() {
        for (owner in listOf("amber", "barbara")) {
            val p = party(owner)
            val before = calculate(p, c6 = false)
            val after = calculate(p)
            assertEquals(before.results.map { it.damageType }, after.results.map { it.damageType })
            assertTrue(after.results.all { it.infusionSourceEffectId == null })
            after.results.filter { it.element == "Pyro" }.forEach { hit -> assertEquals(.15, hit.damageBonus.elementalBonus, 1e-9) }
        }
    }
    @Test fun `unoverrideable conversions remain active and physical alternatives can still be infused`() {
        for ((owner, ownEffect, element) in listOf(Triple("alhaitham", "mirrorInfusion", "Dendro"), Triple("aratakiitto", "ragingOniKing", "Geo"))) {
            val p = party(owner)
            val active = calculate(p, emptyMap(), true, true, ownEffect)
            val attacks = active.results.filter { it.formulaId.startsWith("normal-") }
            assertTrue(attacks.all { it.element == element && it.infusionSourceEffectId == null })
            assertTrue(calculate(p).results.filter { it.formulaId.startsWith("normal-") }.all { it.element == "Pyro" })
        }
    }
    @Test fun `converted hits expose Pyro reactions`() {
        val r = calculate(party("xingqiu"))
        val hit = r.results.first { it.formulaId == "normal-1" }
        assertTrue(hit.reactions.any { it.key.contains("vaporize") })
    }
    @Test fun `infusion input toggle disables conversion independently of the field Pyro bonus`() {
        val p = party("albedo")
        val r = calculate(p, mapOf("character.bennett.pyroInfusionActive" to 0.0))
        assertEquals("Physical", r.results.first { it.formulaId == "normal-1" }.element)
        assertEquals(15.0, r.effectiveStats["pyroDamageBonus"])
        val definition = p.inputs.first { it.id == "character.bennett.pyroInfusionActive" }
        assertEquals(0.0, definition.min)
        assertEquals(1.0, definition.max)
        assertEquals(1.0, definition.step)
    }
    @Test fun `an explicitly overrideable elemental attack can change while locked attacks remain unchanged`() {
        val original = party("alhaitham")
        val first = original.talents.first().formulas.first { it.damageType == "directDendro" }
        val editable = original.copy(talents = original.talents.map { t -> t.copy(formulas = t.formulas.map { f ->
            if (f.id == first.id) f.copy(infusionRule = "overrideable") else f
        }) })
        assertEquals("Dendro", calculate(original, emptyMap(), true, true, "mirrorInfusion").results.first { it.formulaId == first.id }.element)
        assertEquals("Pyro", calculate(editable, emptyMap(), true, true, "mirrorInfusion").results.first { it.formulaId == first.id }.element)
    }
    @Test fun `automatic C6 activation requires the owned constellation`() {
        val p = party("albedo")
        for ((constellation, expected) in listOf(5.0 to "Physical", 6.0 to "Pyro")) {
            val result = service.calculate(p, DamageCalculationRequestV2(mapOf("character.bennett.bennettConstellation" to constellation)))
            assertEquals(expected, result.results.first { it.formulaId == "normal-1" }.element)
        }
    }
    @Test fun `higher snapshot priority wins and conflicting equal priorities are rejected`() {
        val p = party("albedo")
        fun modified(priority: Int) = p.copy(effects = p.effects + DamageEffectV2("testHydro", "Test Hydro", "Test", "Test",
            activation = EffectActivationV2("always"), formulaModifiers = listOf(FormulaModifierV2(
                FormulaTargetsV2(anyTags = listOf("normalAttack")), infusion = WeaponInfusionV2("directHydro", priority = priority)))))
        val r = calculate(modified(11), emptyMap(), true, true, "testHydro")
        assertEquals("Hydro", r.results.first { it.formulaId == "normal-1" }.element)
        assertFailsWith<IllegalArgumentException> { calculate(modified(10), emptyMap(), true, true, "testHydro") }
    }
}
