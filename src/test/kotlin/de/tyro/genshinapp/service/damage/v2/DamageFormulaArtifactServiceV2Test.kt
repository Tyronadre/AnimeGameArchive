package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.model.PlayerArtifact
import org.springframework.core.io.ClassPathResource
import kotlin.test.*

class DamageFormulaArtifactServiceV2Test {
    private val mapper = jacksonObjectMapper()
    private val service = DamageFormulaServiceV2(mapper)
    private val teams = DamageFormulaTeamServiceV2(mapper)
    private val artifacts = DamageFormulaArtifactServiceV2(mapper)
    private val base = teams.compose(assertNotNull(service.profile("nefer")))
    private val slots = listOf("flower", "plume", "sands", "goblet", "circlet")

    private fun gear(owner: String, set: String, count: Int = 4) = slots.take(count).map {
        PlayerArtifact(set, it, 20, 5, "eleMas", owner, false, emptyList(), null, false, false)
    }
    private fun profile(vararg sets: Pair<String, String>) = artifacts.attach(base, sets.flatMap { gear(it.first, it.second) })
    private fun id(owner: String, set: String, effect: String = "4pc") = "artifact.$owner.$set.$effect"
    private fun calculate(profile: DamageFormulaProfileV2, inputs: Map<String, Double> = emptyMap(),
                          enabled: Set<String>? = null) = service.calculate(profile, DamageCalculationRequestV2(
        inputs = inputs, enabledEffects = profile.effects.associate {
            it.id to (enabled?.contains(it.id) ?: it.id.startsWith("artifact."))
        },
    ))
    private fun DamageCalculationV2.hit(id: String) = results.first { it.formulaId == id }

    @Test
    fun `every catalog set has authored formulas that compile at all piece thresholds for carry and support`() {
        val catalogKeys = ClassPathResource("data/artifact-set-bonuses.json").inputStream.use {
            mapper.readTree(it).path("sets").map { set -> set.path("setKey").asText() }.toSet()
        }
        assertEquals(61, catalogKeys.size)
        assertEquals(catalogKeys, artifacts.allDefinitions().map { it.key }.toSet())
        artifacts.allDefinitions().forEach { set ->
            for (owner in listOf("nefer", "lauma")) for (pieces in listOf(1, 2, 4, 5)) {
                val profile = artifacts.attach(base, gear(owner, set.key, pieces))
                val result = calculate(profile)
                assertTrue(result.results.all { it.average.isFinite() && it.average >= 0 }, "${set.key} / $owner / $pieces")
                assertTrue(profile.effects.filter { it.artifactOrigin != null }.all { effect ->
                    effect.artifactOrigin!!.requiredPieces <= pieces || effect.id !in result.activeEffectIds
                })
            }
        }
    }

    @Test
    fun `piece counts come from equipment and cannot be bypassed by request toggles`() {
        val equipped = gear("nefer", "WanderersTroupe", 2) +
            gear("nefer", "GildedDreams", 4).drop(2) + gear("lauma", "DeepwoodMemories")
        val profile = artifacts.attach(teams.compose(assertNotNull(service.profile("nefer")), emptySet()), equipped)
        assertEquals(listOf("nefer"), profile.artifactLoadouts.map { it.characterKey })
        assertEquals(2, profile.artifactLoadouts.single().sets.size)
        val result = calculate(profile)
        assertEquals(1160.0, result.effectiveStats["elementalMastery"])
        assertFalse(id("nefer", "wandererstroupe") in result.activeEffectIds)
        assertFalse(id("nefer", "gildeddreams") in result.activeEffectIds)
        assertFalse(profile.effects.any { it.artifactOrigin?.characterKey == "lauma" })
        val five = artifacts.attach(base, gear("nefer", "wandererstroupe", 5))
        assertEquals(1080.0, calculate(five).effectiveStats["elementalMastery"])
        assertEquals(calculate(base).results.map { it.copy(reactions = emptyList()) }, calculate(profile, enabled = emptySet()).results.map { it.copy(reactions = emptyList()) })
    }

    @Test
    fun `only currently activatable two and four piece bonuses are exposed`() {
        val one = artifacts.attach(base, gear("nefer", "deepwoodmemories", 1))
        assertTrue(one.artifactLoadouts.first { it.characterKey == "nefer" }.sets.isEmpty())
        assertFalse(one.effects.any { it.artifactOrigin?.characterKey == "nefer" })

        val three = artifacts.attach(base, gear("nefer", "deepwoodmemories", 3))
        assertEquals(listOf(2), three.effects.mapNotNull { it.artifactOrigin }
            .filter { it.characterKey == "nefer" }.map { it.requiredPieces }.distinct())
        assertEquals(1, three.artifactLoadouts.first { it.characterKey == "nefer" }.sets.size)

        val four = artifacts.attach(base, gear("nefer", "deepwoodmemories", 4))
        assertEquals(setOf(2, 4), four.effects.mapNotNull { it.artifactOrigin }
            .filter { it.characterKey == "nefer" }.map { it.requiredPieces }.toSet())
    }

    @Test
    fun `actual Lunar party sets grant Intent plus Chant once and do not leak off-field wearer bonus`() {
        val profile = profile("nefer" to "nightoftheskysunveiling", "lauma" to "aubadeofmorningstarandmoon",
            "nahida" to "deepwoodmemories", "columbina" to "silkenmoonsserenade")
        val result = calculate(profile, mapOf("critRate" to 52.3))
        assertEquals(82.3, result.effectiveStats.getValue("critRate"), 1e-8)
        assertEquals(1200.0, result.effectiveStats["elementalMastery"])
        assertEquals(.20, result.hit("shades-hit-1").damageBonus.authoredBonus, 1e-8)
        assertEquals(.30, result.hit("shades-hit-1").resistanceMitigation.reduction, 1e-8)
        assertEquals(0.0, result.hit("skill-hit").damageBonus.authoredBonus)
        assertFalse(id("lauma", "aubadeofmorningstarandmoon") in result.hit("shades-hit-1").appliedEffectIds)
        val noChant = calculate(profile, enabled = result.activeEffectIds - id("columbina", "silkenmoonsserenade"))
        assertEquals(.10, noChant.hit("shades-hit-1").damageBonus.authoredBonus, 1e-8)
        assertEquals(1080.0, noChant.effectiveStats["elementalMastery"])
    }

    @Test
    fun `duplicate party sets do not stack but independent sets do`() {
        val doubleChant = profile("lauma" to "silkenmoonsserenade", "columbina" to "silkenmoonsserenade")
        val chant = calculate(doubleChant)
        assertEquals(1120.0, chant.effectiveStats["elementalMastery"])
        assertEquals(.1, chant.hit("shades-hit-1").damageBonus.authoredBonus, 1e-8)
        assertEquals(1, chant.activeEffectIds.count { it.endsWith("silkenmoonsserenade.4pc") })
        val doubleDeepwood = calculate(profile("lauma" to "deepwoodmemories", "nahida" to "deepwoodmemories"))
        assertEquals(.3, doubleDeepwood.hit("skill-hit").resistanceMitigation.reduction, 1e-8)
        val p = profile("lauma" to "noblesseoblige", "nahida" to "noblesseoblige", "columbina" to "tenacityofthemillelith")
        val result = calculate(p, mapOf("attack" to 1300.0, "baseAttack" to 886.0))
        assertEquals(1300.0 + .4 * 886, result.effectiveStats.getValue("attack"), 1e-8)
    }

    @Test
    fun `support EM set bonuses are resolved before Nahidas converted share`() {
        val p = profile("lauma" to "instructor")
        val selected = p.effects.filter { it.artifactOrigin != null }.map { it.id }.toSet() + "character.nahida.nahidaCompassionIlluminated"
        val r = calculate(p, mapOf("elementalMastery" to 100.0, "character.lauma.laumaElementalMastery" to 600.0,
            "character.nahida.nahidaElementalMastery" to 200.0, "character.columbina.columbinaElementalMastery" to 0.0), selected)
        assertEquals(800.0, r.effectiveStats["character.lauma.laumaElementalMastery"])
        assertEquals(420.0, r.effectiveStats["elementalMastery"])
        assertEquals(200.0, r.statChanges.first { it.effectId == "character.nahida.nahidaCompassionIlluminated" }.amount)
    }

    @Test
    fun `Gilded uses selected allies and Emblem reads effective ER with its cap`() {
        val p = profile("nefer" to "gildeddreams")
        val r = calculate(p, mapOf("baseAttack" to 886.0))
        assertEquals(1130.0, r.effectiveStats["elementalMastery"])
        assertEquals(1300.0 + .28 * 886, r.effectiveStats.getValue("attack"), 1e-8)
        val solo = artifacts.attach(teams.compose(assertNotNull(service.profile("nefer")), emptySet()), gear("nefer", "gildeddreams"))
        assertEquals(1080.0, calculate(solo).effectiveStats["elementalMastery"])
        val emblem = profile("nefer" to "emblemofseveredfate")
        assertEquals(.3, calculate(emblem, mapOf("veilStacks" to 0.0)).hit("burst-hit-1").damageBonus.authoredBonus, 1e-8)
        val energyRecharge = emblem.characterStatInputs.getValue("nefer").getValue("energyRecharge")
        assertEquals(.75, calculate(emblem, mapOf(energyRecharge to 400.0, "veilStacks" to 0.0))
            .hit("burst-hit-1").damageBonus.authoredBonus, 1e-8)
    }

    @Test
    fun `attack classification and weapon restrictions keep ordinary bonuses off Lunar damage`() {
        val wanderer = calculate(profile("nefer" to "wandererstroupe"))
        assertEquals(.35, wanderer.hit("nefer-hit-1").damageBonus.authoredBonus, 1e-8)
        assertEquals(0.0, wanderer.hit("shades-hit-1").damageBonus.authoredBonus)
        assertEquals(0.0, wanderer.hit("skill-hit").damageBonus.authoredBonus)
        val troupe = calculate(profile("nefer" to "goldentroupe"))
        assertEquals(.45, troupe.hit("skill-hit").damageBonus.authoredBonus, 1e-8)
        assertEquals(0.0, troupe.hit("nefer-hit-1").damageBonus.authoredBonus)
        val gladiator = calculate(profile("nefer" to "gladiatorsfinale"))
        assertFalse(id("nefer", "gladiatorsfinale") in gladiator.activeEffectIds)
        val vv = calculate(profile("nahida" to "viridescentvenerer"))
        assertEquals(0.0, vv.hit("shades-hit-1").resistanceMitigation.reduction)
    }

    @Test
    fun `stack inputs clamp and HP conditions deactivate bonuses`() {
        val p = profile("nefer" to "marechausseehunter")
        val r = calculate(p, mapOf("critRate" to 5.0, "artifact.nefer.marechausseehunter.stacks" to 999.0))
        assertEquals(41.0, r.effectiveStats["critRate"])
        assertEquals(3.0, r.inputs["artifact.nefer.marechausseehunter.stacks"])
        val berserker = profile("nefer" to "berserker")
        assertEquals(17.0, calculate(berserker, mapOf("critRate" to 5.0, "artifact.nefer.berserker.hpPercent" to 70.0)).effectiveStats["critRate"])
        val codex = artifacts.attach(base, gear("nefer", "obsidiancodex", 2))
        assertEquals(.15, calculate(codex, mapOf("artifact.nefer.obsidiancodex.nightsoul" to 1.0)).hit("skill-hit").damageBonus.authoredBonus, 1e-8)
    }

    @Test
    fun `Clam is a capped Physical noncrit proc independent of carry damage bonuses and Dendro shred`() {
        val p = profile("lauma" to "oceanhuedclam", "nahida" to "deepwoodmemories")
        val all = p.effects.map { it.id }.toSet()
        val r = calculate(p, mapOf("elementalDamageBonus" to 300.0,
            "critRate" to 100.0, "critDamage" to 500.0, "enemyResistance" to -100.0,
            "enemyPhysicalResistance" to 10.0, "artifact.lauma.oceanhuedclam.recordedHealing" to 99999.0), all)
        val bubble = r.hit("artifact.lauma.oceanhuedclam.foam")
        assertEquals(27000.0, bubble.base.value)
        assertEquals(24300.0, bubble.nonCritical, 1e-8)
        assertEquals(bubble.nonCritical, bubble.critical)
        assertEquals(bubble.nonCritical, bubble.average)
        assertEquals(0.0, bubble.resistanceMitigation.reduction)
        assertEquals(1.0, bubble.defenseMitigation.multiplier)
        assertEquals(1.0, bubble.damageBonus.multiplier)
        assertEquals(1.0, bubble.elevation.multiplier)
        val disabled = calculate(p, enabled = emptySet())
        assertFalse(disabled.results.any { it.formulaId.endsWith(".foam") })
    }

    @Test
    fun `Echoes and Song add to base before bonuses without modifying Lunar or teammate talent damage`() {
        val p = profile("nefer" to "echoesofanoffering", "lauma" to "songofdayspast")
        val r = calculate(p, mapOf("attack" to 1000.0, "baseAttack" to 0.0))
        val plain = calculate(p, mapOf("attack" to 1000.0), emptySet())
        assertEquals(1900.0, r.hit("normal-1").base.value - plain.hit("normal-1").base.value, 1e-8)
        assertEquals(1200.0, r.hit("skill-hit").base.value - plain.hit("skill-hit").base.value, 1e-8)
        assertEquals(plain.hit("shades-hit-1").base.value, r.hit("shades-hit-1").base.value)
        val offFieldEchoes = calculate(profile("lauma" to "echoesofanoffering"))
        assertEquals(0.0, offFieldEchoes.hit("normal-1").base.additiveValue)
    }

    @Test
    fun `empty equipment and unknown sets remain explicit without invented bonuses`() {
        val empty = artifacts.attach(base, emptyList())
        assertEquals(4, empty.artifactLoadouts.size)
        assertTrue(empty.artifactLoadouts.all { it.sets.isEmpty() })
        val unknown = artifacts.attach(base, gear("nefer", "FutureSet"))
        assertFalse(unknown.artifactLoadouts.first { it.characterKey == "nefer" }.sets.single().supported)
        assertEquals(base.effects, unknown.effects)
        assertFailsWith<IllegalArgumentException> { artifacts.attach(base, gear("nefer", "instructor") + gear("nefer", "deepwoodmemories")) }
    }

    @Test
    fun `artifact formulas accept a selected character without a support profile`() {
        val amber = DamageTeamMemberV2("amber", "Amber", "Pyro", 0,
            "/media/characters/amber/icon", "Bow")
        val party = base.copy(
            teamMembers = base.teamMembers + amber,
            selectedTeam = setOf("nefer", "amber"),
            supportProfiles = emptyList(),
        )
        val attached = artifacts.attach(party, gear("amber", "deepwoodmemories"))
        assertTrue(attached.artifactLoadouts.single { it.characterKey == "amber" }.sets.single().supported)
        assertTrue(attached.effects.any { it.artifactOrigin?.characterKey == "amber" })
    }
}
