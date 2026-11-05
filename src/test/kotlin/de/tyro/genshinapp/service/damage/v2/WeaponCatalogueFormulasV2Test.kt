package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.model.PlayerWeapon
import kotlin.test.*

class WeaponCatalogueFormulasV2Test {
    private val mapper = jacksonObjectMapper()
    private val calculator = DamageFormulaServiceV2(mapper)
    private val weapons = DamageFormulaWeaponServiceV2(mapper, DamageFormulaArtifactServiceV2(mapper))
    private val stats = mapOf("attack" to 2000.0, "baseAttack" to 800.0, "maxHp" to 30000.0,
        "baseHp" to 10000.0, "defense" to 1800.0, "baseDefense" to 900.0, "elementalMastery" to 200.0,
        "critRate" to 50.0, "critDamage" to 100.0, "energyRecharge" to 200.0, "physicalDamageBonus" to 50.0)
    private val base = CharacterStandardInputsV3.complete(DamageFormulaProfileV2(2,"nefer","nefer","Weapon test","Pyro",
        inputDefaults = stats,
        damageTypes = listOf(DamageTypeDefinitionV2("testPyro","Pyro")),
        talents = listOf(DamageTalentV2("attacks","Attacks",listOf("normalAttack","chargedAttack","elementalSkill","elementalBurst").map {
            DamageFormulaV2(it,it,"testPyro",tags=listOf(it,"noReaction"),base=FormulaExpressionV2("input",input="attack"))
        })),
        teamMembers = listOf(DamageTeamMemberV2("nefer","Carry","Pyro",0,weaponType="Sword",region="Liyue"),
            DamageTeamMemberV2("support","Support","Hydro",2,weaponType="Catalyst",region="Natlan")),
        selectedTeam = setOf("nefer","support"),weaponType="Sword",
        teamContext = mapOf("pyroCount" to 1.0,"hydroCount" to 1.0,"teamSize" to 2.0,"moonsignLevel" to 2.0,"hexereiCount" to 2.0)))
    private fun equip(key: String, rank: Int=1, owner: String="nefer", profile: DamageFormulaProfileV2=base) =
        weapons.attach(profile,listOf(PlayerWeapon(key,90,6,rank,owner,false)))
    private fun calc(key: String, rank: Int=1, controls: Map<String,Double> = emptyMap()) =
        calculator.calculate(equip(key,rank),DamageCalculationRequestV2(controls.mapKeys { "weapon.nefer.$key.${it.key}" }))

    @Test fun `every bundled weapon validates with zero and maximum controls at both refinement endpoints`() {
        val definitions=weapons.allDefinitions()
        assertEquals(252,definitions.size)
        for (definition in definitions) for (owner in listOf("nefer","support")) for (rank in listOf(1,5)) {
            val profile=equip(definition.key,rank,owner)
            val prefix="weapon.$owner.${definition.key}."
            try {
                for (maximum in listOf(false,true)) {
                    val controls=profile.inputs.filter { it.id.startsWith(prefix) && !it.id.endsWith(".refinement") }
                        .associate { it.id to if(maximum) it.max else it.min }
                    val result=calculator.calculate(profile,DamageCalculationRequestV2(controls))
                    assertTrue(result.results.all { it.average.isFinite() && it.average>=0 })
                    assertTrue(result.effectiveStats.values.all { it.isFinite() })
                }
            } catch (error: Exception) { throw AssertionError("${definition.key}, R$rank, $owner",error) }
        }
    }

    @Test fun `percentage buffs use base stats and stat conversions use final HP and ER`() {
        assertEquals(2160.0,calc("wolfsgravestone").effectiveStats.getValue("attack"),1e-8)
        assertEquals(2480.0,calc("wolfsgravestone",controls=mapOf("partyBuff" to 1.0)).effectiveStats.getValue("attack"),1e-8)
        val homa=calc("staffofhoma",controls=mapOf("lowHp" to 1.0))
        assertEquals(32000.0,homa.effectiveStats.getValue("maxHp"),1e-8)
        assertEquals(2576.0,homa.effectiveStats.getValue("attack"),1e-8)
        val engulfing=calc("engulfinglightning",controls=mapOf("burst" to 1.0))
        assertEquals(230.0,engulfing.effectiveStats.getValue("energyRecharge"),1e-8)
        assertEquals(2291.2,engulfing.effectiveStats.getValue("attack"),1e-8)
    }

    @Test fun `attack classifications and non-linear stacks select the correct damage layer`() {
        val rust=calc("rust",5).results.associateBy { it.formulaId }
        assertEquals(.8,rust.getValue("normalAttack").damageBonus.authoredBonus,1e-8)
        assertEquals(-.1,rust.getValue("chargedAttack").damageBonus.authoredBonus,1e-8)
        assertEquals(0.0,rust.getValue("elementalSkill").damageBonus.authoredBonus,1e-8)
        for ((count,expected) in listOf(0 to 0.0,1 to .12,2 to .24,3 to .4)) {
            assertEquals(expected,calc("thunderingpulse",controls=mapOf("stacks" to count.toDouble())).results.first().damageBonus.authoredBonus,1e-8)
        }
        assertEquals(240.0,calc("lightoffoliarincision",controls=mapOf("active" to 1.0)).results.first().base.additiveValue,1e-8)
        val crimson=calc("crimsonmoonssemblance",controls=mapOf("bondOfLife" to 30.0))
        assertEquals(.36,crimson.results.first().damageBonus.authoredBonus,1e-8)
    }

    @Test fun `party handoff uses the recipients base attack and never buffs the owner`() {
        val key="thrillingtalesofdragonslayers"
        val p=equip(key,5,"support")
        val active=calculator.calculate(p,DamageCalculationRequestV2(mapOf("weapon.support.$key.handoff" to 1.0,
            "weaponStat.support.baseAttack" to 100.0)))
        assertEquals(2384.0,active.effectiveStats.getValue("attack"),1e-8)
        assertEquals(2000.0,calc(key,5,mapOf("handoff" to 1.0)).effectiveStats.getValue("attack"),1e-8)
    }

    @Test fun `weapon procs are separate Physical hits with wearer stats and no infusion or support leakage`() {
        val profile=equip("skywardharp",5)
        val result=calculator.calculate(profile,DamageCalculationRequestV2(mapOf("weapon.nefer.skywardharp.proc" to 1.0)))
        val hit=result.results.single { it.formulaId.endsWith("skywardharp.proc") }
        assertEquals("Physical",hit.element)
        assertEquals(2500.0,hit.base.value,1e-8)
        assertEquals(.5,hit.damageBonus.innateBonus,1e-8)
        assertTrue(hit.reactions.isEmpty())
        assertNull(hit.infusionSourceEffectId)
        assertFalse(equip("skywardharp",5,"support").talents.flatMap { it.formulas }.any { it.id.contains("skywardharp") })
        val messenger=calc("messenger",controls=mapOf("proc" to 1.0)).results.single { it.formulaId.endsWith("messenger.proc") }
        assertEquals(messenger.critical,messenger.average,1e-8)
    }

    @Test fun `Hexerei extension and off-field snapshots use the eligible damage recipient`() {
        val key="angelosheptades"
        val profile=equip(key,1,profile=base.copy(teamContext=base.teamContext + ("character.nefer.hexerei" to 1.0)))
        val controls=mapOf("weapon.nefer.$key.pathfinder" to 1.0,"weapon.nefer.$key.recipientOffField" to 1.0)
        val off=calculator.calculate(profile,DamageCalculationRequestV2(controls))
        assertEquals(.1048,off.results.first().damageBonus.authoredBonus,1e-8)
        val ineligible=calculator.calculate(equip(key),DamageCalculationRequestV2(controls))
        assertEquals(0.0,ineligible.results.first().damageBonus.authoredBonus,1e-8)
        assertEquals(2307.2,calc("calamityqueller",controls=mapOf("stacks" to 6.0,"offField" to 1.0)).effectiveStats.getValue("attack"),1e-8)
    }

    @Test fun `Hakushin does not give a foreign element buff to an infused character`() {
        val pyro=DamageTypeDefinitionV2("testPyro","Pyro",elementalBonusInput="pyroDamageBonus")
        val cryoCarry=CharacterStandardInputsV3.complete(base.copy(element="Cryo",inputs=emptyList(),statBindings=emptyMap(),damageTypes=listOf(pyro)))
        val wrongRecipient=calculator.calculate(equip("hakushinring",owner="support",profile=cryoCarry),
            DamageCalculationRequestV2(mapOf("weapon.support.hakushinring.pyro" to 1.0)))
        assertEquals(0.0,wrongRecipient.results.first().damageBonus.authoredBonus,1e-8)
        val matching=calculator.calculate(equip("hakushinring",owner="support"),
            DamageCalculationRequestV2(mapOf("weapon.support.hakushinring.pyro" to 1.0)))
        assertEquals(.1,matching.results.first().damageBonus.authoredBonus,1e-8)
    }
}
