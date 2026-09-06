package de.tyro.genshinapp.service.damage

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DamageCalculatorServiceTest {
    private val service = DamageCalculatorService(jacksonObjectMapper())
    private val profile = assertNotNull(service.find("nefer"))

    @Test
    fun `Nefer profile resolves every direct talent damage instance`() {
        assertEquals("6.1", profile.dataVersion)
        assertEquals(listOf(8, 8, 2), profile.talents.map { it.instances.size })
        assertEquals(listOf(3, 17, 11, 3), profile.effectSections.map { it.effects.size })
        assertEquals(listOf("nefer", "lauma", "nahida", "columbina"), profile.defaultTeam)
        assertEquals(4, profile.teamMembers.size)
        assertEquals(
            "lunarReaction",
            profile.talents[1].instances.first { it.id == "phantasm-shades-1" }.damageModel,
        )
        val buildInputs = profile.inputSections.flatMap(DamageInputSection::inputs)
        val attackInput = buildInputs.first { it.id == "attack" }
        assertEquals("ATK", attackInput.formulaLabel)
        assertEquals("totalAttack", attackInput.initialValueSource?.valueKey)
        val dendroBonusInput = buildInputs.first { it.id == "elementalDamageBonus" }
        assertEquals(0.0, dendroBonusInput.defaultValue)
        assertEquals("stat:dendro_dmg_", dendroBonusInput.initialValueSource?.valueKey)

        val normalAttack = profile.talents.first().instances.first()
        assertEquals(0.380712, normalAttack.terms.single().values.first(), absoluteTolerance = 0.0000001)

        val skill = profile.talents[1].instances.first()
        assertEquals(listOf("attack", "elementalMastery"), skill.terms.map { it.stat })
        assertEquals(1.52768, skill.terms[1].values.first(), absoluteTolerance = 0.0000001)

        val burst = profile.talents.last().instances.first()
        assertEquals("veilStacks", burst.bonusTerms.single().input)
        assertEquals(0.13, burst.bonusTerms.single().values.first(), absoluteTolerance = 0.0000001)
    }

    @Test
    fun `direct damage applies hit count defense resistance and crit`() {
        val values = service.defaultValues(profile) + disabledEffects() + mapOf(
            "characterLevel" to 90.0,
            "enemyLevel" to 90.0,
            "attack" to 1000.0,
            "elementalMastery" to 0.0,
            "elementalDamageBonus" to 0.0,
            "otherDamageBonus" to 0.0,
            "enemyResistance" to 0.0,
            "critRate" to 100.0,
            "critDamage" to 50.0,
            "normalTalentLevel" to 1.0,
        )

        val result = service.calculate(profile, values)
        val firstHit = result.resultsById.getValue("normal-1")
        val doubleHit = result.resultsById.getValue("normal-3")

        assertEquals(190.356, firstHit.nonCritical, absoluteTolerance = 0.0001)
        assertEquals(285.534, firstHit.critical, absoluteTolerance = 0.0001)
        assertEquals(firstHit.critical, firstHit.average, absoluteTolerance = 0.0001)
        assertEquals(252.4, doubleHit.nonCritical, absoluteTolerance = 0.0001)
        assertEquals(0.5, result.defenseMultiplier, absoluteTolerance = 0.0001)
        assertEquals(1.0, result.resistanceMultiplier, absoluteTolerance = 0.0001)
    }

    @Test
    fun `Nefer passive and weapon effects honor dependencies and synergy`() {
        val baseValues = service.defaultValues(profile) + disabledEffects() + mapOf(
            "attack" to 1000.0,
            "elementalMastery" to 100.0,
            "elementalDamageBonus" to 0.0,
            "otherDamageBonus" to 0.0,
            "enemyResistance" to 0.0,
            "critRate" to 20.0,
            "critDamage" to 50.0,
            "skillTalentLevel" to 1.0,
            "burstTalentLevel" to 1.0,
        )
        val dependencyBlocked = service.calculate(
            profile,
            baseValues + mapOf("aWagerOfMoonlight" to 1.0),
        )
        val buffed = service.calculate(
            profile,
            baseValues + mapOf(
                "ascendantGleam" to 1.0,
                "aWagerOfMoonlight" to 1.0,
                "reliquaryOfTruth" to 1.0,
                "secretOfLies" to 1.0,
                "moonOfTruth" to 1.0,
                "veilStacks" to 3.0,
            ),
        )

        assertEquals(100.0, dependencyBlocked.effectiveStats.getValue("elementalMastery"))
        assertTrue("aWagerOfMoonlight" !in dependencyBlocked.activeEffectIds)
        assertEquals(320.0, buffed.effectiveStats.getValue("elementalMastery"))
        assertEquals(28.0, buffed.effectiveStats.getValue("critRate"))
        assertEquals(86.0, buffed.effectiveStats.getValue("critDamage"))
        assertTrue("aWagerOfMoonlight" in buffed.activeEffectIds)
        assertTrue(
            buffed.resultsById.getValue("skill").nonCritical >
                dependencyBlocked.resultsById.getValue("skill").nonCritical,
        )
    }

    @Test
    fun `Nefer lunar bloom shades use passive EM bonus and ignore DEF and dendro bonus`() {
        val values = service.defaultValues(profile) + disabledEffects() + mapOf(
            "attack" to 1000.0,
            "elementalMastery" to 1000.0,
            "elementalDamageBonus" to 100.0,
            "otherDamageBonus" to 0.0,
            "enemyResistance" to 0.0,
            "critRate" to 0.0,
            "critDamage" to 0.0,
            "skillTalentLevel" to 1.0,
            "dusklitEaves" to 1.0,
        )

        val shade = service.calculate(profile, values)
            .resultsById.getValue("phantasm-shades-1")

        assertEquals(
            960.0 * 1.14 * 3.0,
            shade.nonCritical,
            absoluteTolerance = 0.001,
        )
    }

    @Test
    fun `selected teammates gate their effects and apply separate lunar damage layers`() {
        val enabledTeamEffects = mapOf(
            "ascendantGleam" to 1.0,
            "laumaFrostgroveSanctuary" to 1.0,
            "laumaLightForTheFrostyNight" to 1.0,
            "laumaNaturesChorus" to 1.0,
            "laumaPaleHymn" to 1.0,
            "nahidaCompassionIlluminated" to 1.0,
            "nahidaNewMoonsEmbrace" to 1.0,
            "nahidaDeepwoodMemories" to 1.0,
            "columbinaLunarDomain" to 1.0,
            "columbinaMoonlightLent" to 1.0,
            "columbinaMoonridgeDew" to 1.0,
        )
        val values = service.defaultValues(profile) + disabledEffects() + enabledTeamEffects + mapOf(
            "attack" to 1000.0,
            "elementalMastery" to 1000.0,
            "elementalDamageBonus" to 0.0,
            "otherDamageBonus" to 0.0,
            "enemyResistance" to 10.0,
            "critRate" to 0.0,
            "critDamage" to 0.0,
            "skillTalentLevel" to 1.0,
            "laumaElementalMastery" to 1000.0,
            "laumaSkillTalentLevel" to 10.0,
            "laumaBurstTalentLevel" to 10.0,
            "nahidaElementalMastery" to 1000.0,
            "columbinaMaxHp" to 35000.0,
            "columbinaBurstTalentLevel" to 10.0,
        )

        val fullTeam = service.calculate(profile, values)
        val withoutLauma = service.calculate(
            profile,
            values,
            teamKeys = listOf("nefer", "nahida", "columbina"),
        )

        assertEquals(1250.0, fullTeam.effectiveStats.getValue("elementalMastery"))
        assertEquals(1.225, fullTeam.resistanceMultiplier, absoluteTolerance = 0.0001)
        assertTrue("laumaPaleHymn" in fullTeam.activeEffectIds)
        assertTrue("laumaPaleHymn" !in withoutLauma.activeEffectIds)
        assertEquals(1.1, withoutLauma.resistanceMultiplier, absoluteTolerance = 0.0001)
        val shade = fullTeam.resultsById.getValue("phantasm-shades-1")
        assertEquals(1.2, shade.critical / shade.nonCritical, absoluteTolerance = 0.0001)
        assertTrue(
            shade.nonCritical > withoutLauma.resultsById.getValue("phantasm-shades-1").nonCritical,
        )
    }

    @Test
    fun `ownership sets constellation defaults while manual previews remain available`() {
        val c0Defaults = service.defaultValues(profile)
        val c3Profile = profile.copy(
            inputSections = profile.inputSections.map { section ->
                section.copy(
                    inputs = section.inputs.map { input ->
                        if (input.id == "neferConstellation") input.copy(defaultValue = 3.0) else input
                    },
                )
            },
        )
        val c3Defaults = service.defaultValues(c3Profile)

        assertTrue((1..6).all { rank -> c0Defaults.getValue("neferC$rank") == 0.0 })
        assertTrue((1..3).all { rank -> c3Defaults.getValue("neferC$rank") == 1.0 })
        assertTrue((4..6).all { rank -> c3Defaults.getValue("neferC$rank") == 0.0 })

        val enabledConstellations = (1..6).associate { rank -> "neferC$rank" to 1.0 }
        val baseValues = service.defaultValues(profile) + disabledEffects() + enabledConstellations + mapOf(
            "ascendantGleam" to 1.0,
            "aWagerOfMoonlight" to 1.0,
            "attack" to 1000.0,
            "elementalMastery" to 100.0,
            "elementalDamageBonus" to 0.0,
            "otherDamageBonus" to 0.0,
            "enemyResistance" to 10.0,
            "critRate" to 0.0,
            "critDamage" to 0.0,
            "veilStacks" to 5.0,
            "skillTalentLevel" to 1.0,
            "burstTalentLevel" to 1.0,
        )

        val c0Preview = service.calculate(profile, baseValues + ("neferConstellation" to 0.0))

        assertTrue((1..6).all { rank -> "neferC$rank" in c0Preview.activeEffectIds })
        assertEquals(300.0, c0Preview.effectiveStats.getValue("elementalMastery"))
        assertEquals(4.0, c0Preview.effectiveStats.getValue("skillTalentLevel"))
        assertEquals(4.0, c0Preview.effectiveStats.getValue("burstTalentLevel"))
        assertEquals(1.05, c0Preview.resistanceMultiplier, absoluteTolerance = 0.0001)
        assertTrue(!c0Preview.resultsById.getValue("phantasm-nefer-2").active)
        assertTrue(c0Preview.resultsById.getValue("phantasm-nefer-2-c6").active)
        assertTrue(c0Preview.resultsById.getValue("phantasm-c6-extra").active)
    }

    @Test
    fun `team constellations apply scoped crit talent buffs and cumulative elevation`() {
        val columbinaEffects = (1..6).associate { rank -> "columbinaC$rank" to 1.0 }
        val values = service.defaultValues(profile) + disabledEffects() + columbinaEffects + mapOf(
            "ascendantGleam" to 1.0,
            "columbinaLunarDomain" to 1.0,
            "nahidaC2" to 1.0,
            "nahidaConstellation" to 2.0,
            "columbinaConstellation" to 6.0,
            "attack" to 1000.0,
            "elementalMastery" to 1000.0,
            "elementalDamageBonus" to 0.0,
            "otherDamageBonus" to 0.0,
            "enemyResistance" to 0.0,
            "critRate" to 0.0,
            "critDamage" to 0.0,
            "skillTalentLevel" to 1.0,
            "columbinaMaxHp" to 35000.0,
            "columbinaBurstTalentLevel" to 10.0,
        )

        val result = service.calculate(profile, values)
        val shadeDefinition = profile.talents[1].instances.first { it.id == "phantasm-shades-1" }
        val shade = result.resultsById.getValue("phantasm-shades-1")
        val direct = result.resultsById.getValue("skill")
        val effectiveEm = result.effectiveStats.getValue("elementalMastery")
        val rawShade = effectiveEm * shadeDefinition.terms.single().values.first()
        val lunarEmBonus = 6.0 * effectiveEm / (effectiveEm + 2000.0)

        assertEquals(1171.5, effectiveEm, absoluteTolerance = 0.0001)
        assertEquals(13.0, result.effectiveStats.getValue("columbinaBurstTalentLevel"))
        assertEquals(rawShade * (1.0 + lunarEmBonus + 0.49) * 1.2, shade.nonCritical, absoluteTolerance = 0.001)
        assertEquals(2.0, shade.critical / shade.nonCritical, absoluteTolerance = 0.0001)
        assertEquals(1.1, shade.average / shade.nonCritical, absoluteTolerance = 0.0001)
        assertEquals(1.8, direct.critical / direct.nonCritical, absoluteTolerance = 0.0001)
    }

    private fun disabledEffects(): Map<String, Double> = profile.effectSections
        .flatMap(DamageEffectSection::effects)
        .associate { effect -> effect.id to 0.0 }
}
