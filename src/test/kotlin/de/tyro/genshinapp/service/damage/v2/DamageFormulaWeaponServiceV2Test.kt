package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.model.PlayerWeapon
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.service.artifact.ArtifactBuildStats
import de.tyro.genshinapp.service.artifact.ArtifactOptimizationService
import de.tyro.genshinapp.service.artifact.OptimizerBaseStats
import de.tyro.genshinapp.service.artifact.OptimizerCombatStatService
import de.tyro.genshinapp.service.catalog.CharacterStatTestData
import de.tyro.genshinapp.service.planning.CharacterTargetService
import de.tyro.genshinapp.service.planning.PlayerPlanningService
import de.tyro.genshinapp.service.player.PlayerEquipmentService
import de.tyro.genshinapp.service.player.PlayerSnapshotStore
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import kotlin.test.*

class DamageFormulaWeaponServiceV2Test {
    private val mapper = jacksonObjectMapper()
    private val calculator = DamageFormulaServiceV2(mapper)
    private val teams = DamageFormulaTeamServiceV2(mapper)
    private val weapons = DamageFormulaWeaponServiceV2(mapper, DamageFormulaArtifactServiceV2(mapper))
    private val base = teams.compose(assertNotNull(calculator.profile("nefer")))
    private fun weapon(key: String, owner: String = "nefer", rank: Int = 1) =
        PlayerWeapon(key, 90, 6, rank, owner, false)
    private fun calculate(profile: DamageFormulaProfileV2, inputs: Map<String, Double> = emptyMap(),
                          disabled: Set<String> = emptySet()) = calculator.calculate(profile,
        DamageCalculationRequestV2(inputs, profile.effects.associate {
            it.id to (it.id.startsWith("weapon.") && it.id !in disabled)
        }))
    private val baseline get() = calculate(base)

    @Test
    fun `current build loads equipped refinement and secondary stats before adding weapon passives once`() {
        val snapshots = mock(PlayerSnapshotStore::class.java)
        val planning = mock(PlayerPlanningService::class.java)
        val optimizer = mock(OptimizerCombatStatService::class.java)
        val artifacts = mock(ArtifactOptimizationService::class.java)
        val savedWeapon = weapon("ReliquaryOfTruth", rank = 5)
        val state = PlayerCharacterState("Nefer", 90, 1, 6, 9, 10, 9)
        val snapshot = PlayerSnapshot(1, "test", Instant.EPOCH, characters = listOf(state), inventory = emptyMap(),
            inventoryNames = emptyMap(), exportedInventoryKeys = 0, artifacts = emptyList(), weapons = listOf(savedWeapon))
        `when`(snapshots.current(1)).thenReturn(snapshot)
        `when`(planning.findCharacterState(snapshot, "nefer")).thenReturn(state)
        val stats = OptimizerBaseStats(emptyMap(), mapOf("critDMG_" to 88.2), emptyMap(), combinedBaseAttack = 800.0)
        `when`(optimizer.resolve(state, savedWeapon, emptyMap())).thenReturn(stats)
        `when`(artifacts.summarizeCurrentBuild(emptyList(), stats, false)).thenReturn(
            ArtifactBuildStats(emptyList(), mapOf("critDMG_" to 176.6, "critRate_" to 5.0, "eleMas" to 100.0),
                0.0, 0.0, true, 1.0, 1.0, true, 0.0, emptyList()))
        val build = DamageFormulaBuildServiceV2(snapshots, planning, PlayerEquipmentService(), artifacts, optimizer,
            mock(CharacterTargetService::class.java), DamageFormulaArtifactServiceV2(mapper), CharacterStatTestData.catalog(), weapons)
        val profile = build.applyCurrentValues(base, 1)
        assertEquals(176.6, profile.inputs.single { it.id == "critDamage" }.defaultValue)
        assertEquals(5.0, profile.inputs.single { it.id.endsWith(".refinement") }.defaultValue)
        val result = calculate(profile)
        assertEquals(340.0, result.effectiveStats["elementalMastery"])
        assertEquals(248.6, result.effectiveStats.getValue("critDamage"), 1e-8)
        assertEquals(21.0, result.effectiveStats["critRate"])
    }

    @Test
    fun `original four definitions compile for every refinement and carry or support wearer`() {
        val original = setOf("reliquaryoftruth", "nightweaverslookingglass", "athousandfloatingdreams", "nocturnescurtaincall")
        assertTrue(weapons.allDefinitions().map { it.key }.containsAll(original))
        weapons.allDefinitions().filter { it.key in original }.forEach { definition ->
            for (rank in 1..5) for (owner in listOf("nefer", "lauma", "columbina")) {
                val profile = weapons.attach(base, listOf(weapon(definition.key, owner, rank)))
                assertEquals(rank.toDouble(), profile.inputs.single { it.id.endsWith(".refinement") }.defaultValue)
                assertTrue(calculate(profile).results.all { it.average.isFinite() })
                assertEquals(base.artifactLoadouts, profile.artifactLoadouts)
                assertTrue(profile.effects.filter { it.id.startsWith("weapon.") }.all { it.artifactOrigin == null })
            }
        }
    }

    @Test
    fun `reliquary buffs scale with refinement and combined bonus requires both active effects`() {
        for (rank in listOf(1, 5)) {
            val profile = weapons.attach(base, listOf(weapon("ReliquaryOfTruth", rank = rank)))
            val prefix = "weapon.nefer.reliquaryoftruth."
            val both = calculate(profile)
            assertEquals(baseline.effectiveStats.getValue("elementalMastery") + 1.5 * (60 + 20 * rank), both.effectiveStats["elementalMastery"])
            assertEquals(baseline.effectiveStats.getValue("critRate") + 6 + 2 * rank, both.effectiveStats["critRate"])
            assertEquals(baseline.effectiveStats.getValue("critDamage") + 1.5 * (18 + 6 * rank), both.effectiveStats["critDamage"])
            val skillOnly = calculate(profile, mapOf(prefix + "moon" to 0.0))
            assertEquals(baseline.effectiveStats.getValue("elementalMastery") + 60 + 20 * rank, skillOnly.effectiveStats["elementalMastery"])
            assertEquals(baseline.effectiveStats["critDamage"], skillOnly.effectiveStats["critDamage"])
            val disabledSkill = calculate(profile, disabled = setOf(prefix + "secret"))
            assertEquals(baseline.effectiveStats["elementalMastery"], disabledSkill.effectiveStats["elementalMastery"])
            assertFalse(prefix + "both" in disabledSkill.activeEffectIds)
        }
    }

    @Test
    fun `nightweaver boosts party Lunar Bloom only with both buffs and strongest copy wins`() {
        val profile = weapons.attach(base, listOf(weapon("NightweaversLookingGlass", "lauma"),
            weapon("NightweaversLookingGlass", "nahida", 5)))
        val result = calculate(profile)
        val hit = result.results.first { it.formulaId == "shades-hit-1" }
        assertEquals(.8, hit.damageBonus.authoredBonus, 1e-8)
        assertEquals(2.4, result.results.first { it.formulaId == "skill-hit" }.reactions.first { it.id == "bloom" }.reactionDamageBonus, 1e-8)
        assertEquals(1, result.activeEffectIds.count { it.endsWith("nightweaverslookingglass.both") })
        assertEquals(0.0, result.results.first { it.formulaId == "skill-hit" }.damageBonus.authoredBonus)
        val one = calculate(profile, mapOf("weapon.nahida.nightweaverslookingglass.verse" to 0.0))
        assertEquals(.4, one.results.first { it.formulaId == "shades-hit-1" }.damageBonus.authoredBonus, 1e-8)
        assertEquals(baseline.effectiveStats["elementalMastery"], result.effectiveStats["elementalMastery"])
        val wearer = weapons.attach(base, listOf(weapon("NightweaversLookingGlass")))
        assertEquals(baseline.effectiveStats.getValue("elementalMastery") + 120, calculate(wearer).effectiveStats["elementalMastery"])
    }

    @Test
    fun `nightweaver supplies Hyperbloom and Burgeon reaction bonuses without ordinary damage bonus`() {
        for ((element, reaction) in listOf("Electro" to "hyperbloom", "Pyro" to "burgeon")) {
            val reactionCarry = base.copy(damageTypes = base.damageTypes.map {
                if (it.id == "directDendro") it.copy(element = element) else it
            })
            val profile = weapons.attach(reactionCarry, listOf(weapon("NightweaversLookingGlass", "lauma", 5)))
            val result = calculate(profile).results.first { it.formulaId == "skill-hit" }
            assertEquals(1.6, result.reactions.first { it.id == reaction }.reactionDamageBonus, 1e-8)
            assertEquals(0.0, result.damageBonus.authoredBonus)
        }
    }

    @Test
    fun `unequipped or unsupported weapons have no effects and duplicate assignments are rejected`() {
        val profile = weapons.attach(base, listOf(weapon("ReliquaryOfTruth").copy(location = null), weapon("unknown")))
        assertFalse(profile.effects.any { it.id.startsWith("weapon.") })
        assertEquals(baseline.results, calculate(profile).results)
        assertFailsWith<IllegalArgumentException> {
            weapons.attach(base, listOf(weapon("ReliquaryOfTruth"), weapon("AThousandFloatingDreams")))
        }
    }

    @Test
    fun `floating dreams counts other members and multiple party EM buffs stack`() {
        val profile = weapons.attach(base, listOf(weapon("AThousandFloatingDreams"),
            weapon("AThousandFloatingDreams", "lauma"), weapon("AThousandFloatingDreams", "nahida", 5)))
        val result = calculate(profile)
        assertEquals(baseline.effectiveStats.getValue("elementalMastery") + 64 + 40 + 48, result.effectiveStats["elementalMastery"])
        assertEquals(.1, result.results.first { it.formulaId == "skill-hit" }.damageBonus.authoredBonus, 1e-8)
        assertEquals(0.0, result.results.first { it.formulaId == "shades-hit-1" }.damageBonus.authoredBonus)
        val solo = weapons.attach(teams.compose(assertNotNull(calculator.profile("nefer")), emptySet()),
            listOf(weapon("AThousandFloatingDreams"), weapon("AThousandFloatingDreams", "lauma")))
        assertEquals(baseline.effectiveStats["elementalMastery"], calculate(solo).effectiveStats["elementalMastery"])
        assertFalse(solo.effects.any { it.id.startsWith("weapon.lauma.") })
    }

    @Test
    fun `support weapon EM and HP feed character conversions and damage layers`() {
        val profile = weapons.attach(base, listOf(weapon("NightweaversLookingGlass", "lauma"),
            weapon("NocturnesCurtainCall", "columbina")))
        val inputs = mapOf("character.columbina.columbinaMaxHp" to 20000.0, "character.columbina.columbinaBaseHp" to 10000.0)
        val active = calculator.calculate(profile, DamageCalculationRequestV2(inputs))
        val inactive = calculator.calculate(profile, DamageCalculationRequestV2(inputs, profile.effects
            .filter { it.id.startsWith("weapon.") }.associate { it.id to false }))
        assertEquals(inactive.effectiveStats.getValue("character.lauma.laumaElementalMastery") + 120, active.effectiveStats["character.lauma.laumaElementalMastery"])
        assertEquals(22400.0, active.effectiveStats["character.columbina.columbinaMaxHp"])
        assertTrue(active.results.first { it.formulaId == "shades-hit-1" }.average > inactive.results.first { it.formulaId == "shades-hit-1" }.average)
    }

    @Test
    fun `nocturne HP adds percentages of base HP and crit applies only to Lunar damage`() {
        for (rank in listOf(1, 5)) {
            val profile = weapons.attach(base, listOf(weapon("NocturnesCurtainCall", rank = rank)))
            val binding = profile.characterStatInputs.getValue("nefer")
            val hp = binding.getValue("maxHp")
            val baseHp = profile.inputs.single { it.initialValueSource?.valueKey == "baseHp" &&
                it.initialValueSource?.characterKey == "nefer" }.id
            val result = calculate(profile, mapOf(hp to 30000.0, baseHp to 10000.0))
            assertEquals(30000.0 + 100 * (8 + 2 * rank + 12 + 2 * rank), result.effectiveStats[hp])
            val wine = "weapon.nefer.nocturnescurtaincall.wine"
            val off = calculate(profile, mapOf(hp to 30000.0, baseHp to 10000.0, wine to 0.0))
            assertEquals(30000.0 + 100 * (8 + 2 * rank), off.effectiveStats[hp])
            assertTrue(result.results.first { it.formulaId == "shades-hit-1" }.average > off.results.first { it.formulaId == "shades-hit-1" }.average)
            assertEquals(off.results.first { it.formulaId == "skill-hit" }.average, result.results.first { it.formulaId == "skill-hit" }.average)
        }
    }
}
