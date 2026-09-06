package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DamageFormulaCharacterProfilesV2Test {
    private val mapper = jacksonObjectMapper()
    private val formulas = DamageFormulaServiceV2(mapper)
    private val teams = DamageFormulaTeamServiceV2(mapper)
    private val keys = listOf("arlecchino", "bennett", "xiangling", "xingqiu", "skirk", "escoffier", "furina", "citlali")

    @Test
    fun `all requested characters have complete calculable profiles`() {
        keys.forEach { key ->
            val profile = assertNotNull(formulas.profile(key), key)
            assertEquals(listOf("normal", "skill", "burst"), profile.talents.map { it.key }, key)
            assertTrue(profile.talents.all { it.formulas.isNotEmpty() }, key)
            assertEquals(6, profile.effects.count { it.category == "constellation" }, key)
            assertTrue(profile.effects.count { it.category == "passive" } >= 3, key)
            val composed = teams.compose(profile)
            assertFalse("nefer" in composed.selectedTeam, key)
            val result = formulas.calculate(composed, DamageCalculationRequestV2())
            assertTrue(result.results.isNotEmpty(), key)
            assertTrue(result.results.all { it.nonCritical.isFinite() && it.critical.isFinite() && it.average.isFinite() }, key)
            assertTrue(result.results.flatMap { it.reactions }.all {
                it.nonCritical.isFinite() && it.critical.isFinite() && it.average.isFinite()
            }, key)
        }
    }

    @Test
    fun `selected team elements expose directional amplifying reactions`() {
        val arlecchinoProfile = teams.compose(assertNotNull(formulas.profile("arlecchino")), setOf("xingqiu"))
        val arlecchino = formulas.calculate(arlecchinoProfile, DamageCalculationRequestV2(
            inputs = mapOf("elementalMastery" to 100.0),
            enabledEffects = arlecchinoProfile.effects.associate { it.id to false },
        ))
        val pyroVaporize = arlecchino.hit("skill-spike").reactions.single { it.id == "vaporize" }
        val expectedPyroMultiplier = 1.5 * (1.0 + 2.78 * 100.0 / 1500.0)
        assertEquals(expectedPyroMultiplier, pyroVaporize.multiplier, 1e-9)
        assertEquals(arlecchino.hit("skill-spike").average * expectedPyroMultiplier, pyroVaporize.average, 1e-6)
        assertEquals(listOf("Xingqiu"), pyroVaporize.auraProviders)

        val reactionBonus = DamageEffectV2(
            id = "testVaporizeBonus",
            name = "Test Vaporize bonus",
            source = "Test",
            description = "Adds 15% Vaporize reaction damage.",
            activation = EffectActivationV2("always"),
            formulaModifiers = listOf(FormulaModifierV2(
                targets = FormulaTargetsV2(anyTags = listOf("vaporize"), damageKinds = listOf("reaction")),
                damageBonus = FormulaContributionV2(FormulaExpressionV2("constant", value = 15.0)),
            )),
        )
        val boostedProfile = arlecchinoProfile.copy(effects = arlecchinoProfile.effects + reactionBonus)
        val boosted = formulas.calculate(boostedProfile, DamageCalculationRequestV2(
            inputs = mapOf("elementalMastery" to 100.0),
            enabledEffects = boostedProfile.effects.associate { it.id to (it.id == reactionBonus.id) },
        )).hit("skill-spike").reactions.single { it.id == "vaporize" }
        assertEquals(0.15, boosted.reactionDamageBonus, 1e-9)
        assertEquals(1.5 * (1.0 + 2.78 * 100.0 / 1500.0 + 0.15), boosted.multiplier, 1e-9)
        assertEquals(listOf(DamageReactionBonusSourceV2(reactionBonus.id, 0.15)), boosted.bonusSources)

        val xingqiuProfile = teams.compose(assertNotNull(formulas.profile("xingqiu")), setOf("bennett"))
        val xingqiu = formulas.calculate(xingqiuProfile, DamageCalculationRequestV2(
            inputs = mapOf("elementalMastery" to 0.0),
            enabledEffects = xingqiuProfile.effects.associate { it.id to false },
        ))
        assertEquals(2.0, xingqiu.hit("skill-hit-1").reactions.single { it.id == "vaporize" }.multiplier, 1e-9)
        assertTrue(xingqiu.hit("rain-wave-2").reactions.isEmpty())

        val citlaliProfile = teams.compose(assertNotNull(formulas.profile("citlali")), setOf("bennett"))
        val citlali = formulas.calculate(citlaliProfile, DamageCalculationRequestV2(
            inputs = mapOf("elementalMastery" to 0.0),
            enabledEffects = citlaliProfile.effects.associate { it.id to false },
        ))
        assertEquals(1.5, citlali.hit("frostfall-storm").reactions.single { it.id == "melt" }.multiplier, 1e-9)
    }

    @Test
    fun `all standard reaction families use their dedicated calculation path`() {
        val reactionIds = linkedSetOf<String>()
        val pyro = calculateReactionScenario(
            key = "arlecchino",
            formulaId = "skill-spike",
            teamElements = listOf("Hydro", "Cryo", "Electro", "Dendro"),
        )
        reactionIds += pyro.reactions.map { it.id }
        assertTrue(pyro.reactions.map { it.id }.toSet()
            .containsAll(setOf("vaporize", "melt", "overloaded", "burning", "burgeon")))
        val overloaded = pyro.reactions.single { it.id == "overloaded" }
        val transformativeBase = 1446.853458 * 2.75 * (1.0 + 16.0 * 100.0 / 2100.0)
        assertEquals(transformativeBase, overloaded.reactionBase!!, 1e-6)
        assertEquals(transformativeBase * 0.9, overloaded.reactionDamage!!, 1e-6)
        assertEquals(overloaded.reactionDamage, overloaded.average)

        val hydro = calculateReactionScenario(
            key = "xingqiu",
            formulaId = "skill-hit-1",
            teamElements = listOf("Pyro", "Cryo", "Electro", "Dendro"),
        )
        reactionIds += hydro.reactions.map { it.id }
        assertEquals("No direct damage", hydro.reactions.single { it.id == "freeze" }.utilityLabel)

        val dendro = calculateReactionScenario(
            key = "nefer",
            formulaId = "skill-hit",
            teamElements = listOf("Electro", "Pyro", "Hydro"),
        )
        reactionIds += dendro.reactions.map { it.id }
        val spread = dendro.reactions.single { it.id == "spread" }
        assertEquals(1446.853458 * 1.25 * (1.0 + 5.0 * 100.0 / 1300.0), spread.reactionBase!!, 1e-6)
        assertTrue(spread.average > dendro.average)

        val electro = calculateReactionScenario(
            key = "arlecchino",
            formulaId = "skill-spike",
            triggerElement = "Electro",
            teamElements = listOf("Pyro", "Hydro", "Cryo", "Dendro"),
        )
        reactionIds += electro.reactions.map { it.id }

        val anemo = calculateReactionScenario(
            key = "arlecchino",
            formulaId = "skill-spike",
            triggerElement = "Anemo",
            teamElements = listOf("Pyro", "Hydro", "Electro", "Cryo"),
        )
        reactionIds += anemo.reactions.map { it.id }
        assertEquals(setOf("Pyro", "Hydro", "Electro", "Cryo"),
            anemo.reactions.filter { it.id == "swirl" }.mapNotNull { it.damageElement }.toSet())

        val geo = calculateReactionScenario(
            key = "arlecchino",
            formulaId = "skill-spike",
            triggerElement = "Geo",
            teamElements = listOf("Hydro"),
        )
        reactionIds += geo.reactions.map { it.id }
        val crystallize = geo.reactions.single { it.id == "crystallize" }
        assertEquals(1851.060358 * (1.0 + 4.44 * 100.0 / 1500.0), crystallize.utilityValue!!, 1e-6)

        val shatter = calculateReactionScenario(
            key = "arlecchino",
            formulaId = "plunge",
            teamElements = listOf("Hydro", "Cryo"),
        )
        reactionIds += shatter.reactions.map { it.id }
        assertTrue(shatter.reactions.any { it.id == "shatter" })

        assertEquals(setOf(
            "vaporize", "melt", "aggravate", "spread", "overloaded", "electroCharged",
            "superconduct", "swirl", "burning", "bloom", "hyperbloom", "burgeon", "shatter",
            "freeze", "quicken", "crystallize",
        ), reactionIds)
    }

    @Test
    fun `character-specific formula layers are resolved`() {
        val arlecchino = calculate("arlecchino", mapOf("attack" to 1000.0, "bondOfLifePercent" to 130.0))
        assertEquals(1000.0 * (.938961 + 2.38 * 1.3), arlecchino.hit("masque-normal-1").base.formulaValue, 1e-6)
        assertFalse(arlecchino.results.any { it.formulaId == "normal-1" })

        val xingqiu = calculate("xingqiu", effects = mapOf("xingqiuC4" to true))
        assertEquals(1.5, xingqiu.hit("skill-hit-1").base.multiplier, 1e-6)

        val skirkProfile = teams.compose(assertNotNull(formulas.profile("skirk")))
        val skirk = formulas.calculate(skirkProfile, DamageCalculationRequestV2(enabledEffects =
            skirkProfile.effects.associate { it.id to (it.id in setOf("allShallWither", "returnToOblivion", "mutualWeaponsMentorship")) }))
        assertEquals(1.7, skirk.hit("warp-normal-1").base.multiplier, 1e-6)
        assertEquals(.2, skirk.hit("warp-normal-1").damageBonus.authoredBonus, 1e-6)

        val furina = calculate("furina", mapOf("maxHp" to 40000.0, "fanfarePoints" to 300.0))
        assertEquals(1.4, furina.hit("usher").base.multiplier, 1e-6)
        assertEquals(1.03, furina.hit("usher").damageBonus.authoredBonus, 1e-6)

        val citlali = calculate("citlali", mapOf("attack" to 0.0, "elementalMastery" to 1000.0))
        assertEquals(900.0, citlali.hit("frostfall-storm").base.value, 1e-6)
        assertEquals(12000.0, citlali.hit("ice-storm").base.value, 1e-6)
    }

    private fun calculate(
        key: String,
        inputs: Map<String, Double> = emptyMap(),
        effects: Map<String, Boolean> = emptyMap(),
    ): DamageCalculationV2 {
        val profile = assertNotNull(formulas.profile(key))
        val constellationEffects = profile.effects.filter { it.category == "constellation" }.associate { it.id to false }
        return formulas.calculate(profile, DamageCalculationRequestV2(inputs, constellationEffects + effects))
    }

    private fun calculateReactionScenario(
        key: String,
        formulaId: String,
        teamElements: List<String>,
        triggerElement: String? = null,
    ): DamageFormulaResultV2 {
        val composed = teams.compose(assertNotNull(formulas.profile(key)))
        val sourceFormula = composed.talents.flatMap { it.formulas }.first { it.id == formulaId }
        val members = buildList {
            add(composed.teamMembers.first { it.key == composed.characterKey }.let { member ->
                triggerElement?.let { member.copy(element = it) } ?: member
            })
            teamElements.forEachIndexed { index, element ->
                add(DamageTeamMemberV2("test-$index", "$element support", element, 0))
            }
        }
        val profile = composed.copy(
            damageTypes = composed.damageTypes.map { damageType ->
                if (triggerElement != null && damageType.id == sourceFormula.damageType) {
                    damageType.copy(element = triggerElement)
                } else damageType
            },
            teamMembers = members,
            selectedTeam = members.mapTo(linkedSetOf()) { it.key },
        )
        val calculation = formulas.calculate(profile, DamageCalculationRequestV2(
            inputs = mapOf("characterLevel" to 90.0, "elementalMastery" to 100.0),
            enabledEffects = profile.effects.associate { it.id to false },
        ))
        return calculation.hit(formulaId)
    }

    private fun DamageCalculationV2.hit(id: String) = results.first { it.formulaId == id }
}
