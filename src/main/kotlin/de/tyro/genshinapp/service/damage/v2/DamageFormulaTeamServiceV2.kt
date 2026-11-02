package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.service.catalog.CharacterCatalogService
import org.springframework.stereotype.Service

@Service
class DamageTeamRosterService(private val characters: CharacterCatalogService, private val registry: CharacterFormulaRegistryV3) {
    fun members(): List<DamageTeamMemberV2> = characters.getCharacters().map {
        DamageTeamMemberV2(it.key, it.name, it.element.orEmpty(), 0, it.iconImageUrl, it.weapon.orEmpty(),
            registry.available(it.key), region = it.region.orEmpty())
    }
}

@Service
class DamageFormulaTeamServiceV2(
    private val objectMapper: ObjectMapper,
    private val roster: DamageTeamRosterService? = null,
    private val registry: CharacterFormulaRegistryV3 = CharacterFormulaRegistryV3(objectMapper),
) {
    private val rules: DamageTeamRulesV2 by reloadableFormulaV2 {
        DamageFormulaResourcesV2.resource("data/damage-formulas-v2/team-rules.json").inputStream.use {
            objectMapper.readValue(it, DamageTeamRulesV2::class.java)
        }
    }

    fun compose(base: DamageFormulaProfileV2, requestedTeam: Set<String>? = null): DamageFormulaProfileV2 {
        val authoredMembers = registry.definitions.values.associate { it.key to registry.member(it) }
        val catalogMembers = roster?.members().orEmpty().map { member -> authoredMembers[member.key]?.let { authored ->
            member.copy(name = authored.name, element = authored.element,
                moonsignContribution = authored.moonsignContribution,
                weaponType = authored.weaponType.ifBlank { member.weaponType })
        } ?: member }
        val baseMember = (catalogMembers + authoredMembers.values).firstOrNull { it.key == base.characterKey }
            ?.let { member -> member.copy(name = base.name, element = base.element,
                moonsignContribution = base.moonsignContribution,
                weaponType = base.weaponType.ifBlank { member.weaponType }) }
            ?: DamageTeamMemberV2(base.characterKey, base.name, base.element, base.moonsignContribution,
                weaponType = base.weaponType, calculatorAvailable = true)
        val members = listOf(baseMember) + (catalogMembers + authoredMembers.values)
            .filter { it.key != base.characterKey }.distinctBy { it.key }.sortedBy { it.name }
        val team = ((requestedTeam ?: base.defaultTeam.toSet()).map { it.trim().lowercase() } + base.characterKey).toSet()
        require(team.size <= 4) { "A team can contain at most four characters" }
        require(team.all { key -> members.any { it.key == key } }) { "Team contains an unknown character" }
        val sourceMembers = members.filter { it.key in team }.sortedWith(compareBy<DamageTeamMemberV2> { it.key != base.characterKey }.thenBy { it.name })
        val sources = sourceMembers.map { registry.definitions[it.key] ?: registry.generic(it) }
        val boundContext = registry.context(sourceMembers)
        val binder = CharacterEffectBinderV3(sources, base.characterKey, boundContext)
        val bound = sources.associate { it.key to binder.bind(it) }
        val carry = bound.getValue(base.characterKey)
        val selected = sources.filter { it.key != base.characterKey }.map { source ->
            val compiled = bound.getValue(source.key)
            DamageSupportProfileV2(2, source.key, source.name, source.element, source.moonsignContribution,
                source.characterDataResource.ifBlank { null }, source.talentParameters, compiled.statBindings, compiled.inputs,
                compiled.effects, source.notes, source.sources, source.weaponType)
        }
        val inputs = (carry.inputs + selected.flatMap { support -> support.inputs.map { input -> input.copy(
            section = input.section ?: "teamStats",
            initialValueSource = input.initialValueSource?.let { it.copy(characterKey = support.key) },
        ) } } + rules.inputs).associateByTo(linkedMapOf()) { it.id }
        inputs.putIfAbsent("baseAttack", DamageFormulaInputV2(
            id = "baseAttack", label = "Base ATK", defaultValue = 0.0,
            min = 0.0, max = 3000.0, step = 1.0,
            initialValueSource = DamageFormulaInitialValueSourceV2("baseAttack", base.characterKey),
            section = "internal", hintTag = "attack",
        ))
        REACTION_DAMAGE_ELEMENTS.forEach { element ->
            if (base.damageTypes.none { it.element.equals(element, ignoreCase = true) }) {
                val id = reactionResistanceInput(element)
                inputs.putIfAbsent(id, DamageFormulaInputV2(
                    id = id,
                    label = "$element RES (reactions)",
                    defaultValue = 10.0,
                    min = -200.0,
                    max = 200.0,
                    step = 1.0,
                    unit = "percentPoints",
                    section = "enemy",
                    hintTag = "enemyResistance",
                ))
            }
        }
        val selectedMembers = members.filter { it.key in team }
        val completeBindings = linkedMapOf<String, MutableMap<String, String>>()
        val visibleBindings = linkedMapOf<String, Map<String, String>>()
        val progressBindings = linkedMapOf<String, Map<String, String>>()
        val buffBindings = linkedMapOf<String, List<String>>()
        selectedMembers.forEach { member ->
            val authored = if (member.key == base.characterKey) carry.statBindings
                else selected.firstOrNull { it.key == member.key }?.statBindings.orEmpty()
            val bindings = authored.toMutableMap()
            if (member.key == base.characterKey) {
                STANDARD_STATS.keys.filter(inputs::containsKey).forEach { stat -> bindings.putIfAbsent(stat, stat) }
            }
            val visible = linkedMapOf<String, String>()
            STANDARD_STATS.forEach { (stat, definition) ->
                val inputId = bindings[stat] ?: "characterStat.${member.key}.$stat".also { id ->
                    bindings[stat] = id
                    inputs[id] = definition.input(member, id)
                }
                val existing = requireNotNull(inputs[inputId]) { "Undefined stat input '$inputId' for '${member.key}'" }
                inputs[inputId] = existing.copy(
                    section = "stats:${member.key}",
                    hintTag = existing.hintTag ?: stat,
                    initialValueSource = existing.initialValueSource
                        ?: definition.source(member)?.let { DamageFormulaInitialValueSourceV2(it, member.key) },
                )
                visible[stat] = inputId
            }
            // Authored non-display bindings such as Columbina's base HP remain available to formulas.
            bindings.forEach bindingLoop@{ (stat, inputId) ->
                val definition = inputs[inputId] ?: return@bindingLoop
                if (definition.initialValueSource == null && stat == "baseHp") {
                    inputs[inputId] = definition.copy(
                        initialValueSource = DamageFormulaInitialValueSourceV2("baseHp", member.key),
                    )
                }
            }
            completeBindings[member.key] = bindings
            visibleBindings[member.key] = visible

            val progress = linkedMapOf<String, String>()
            PROGRESS_INPUTS.forEach { (progressKey, definition) ->
                val existing = inputs.values.firstOrNull { input ->
                    val source = input.initialValueSource ?: return@firstOrNull false
                    source.valueKey == definition.valueKey &&
                        (source.characterKey == member.key ||
                            (member.key == base.characterKey && source.characterKey == null))
                }
                val inputId = existing?.id ?: "characterProgress.${member.key}.$progressKey".also { id ->
                    inputs[id] = definition.input(member, id)
                }
                inputs[inputId] = requireNotNull(inputs[inputId]).copy(
                    section = "progress:${member.key}",
                    hintTag = if (progressKey == "characterLevel") "characterLevel" else "talentLevel",
                )
                progress[progressKey] = inputId
            }
            progressBindings[member.key] = progress

            val supportInputIds = selected.firstOrNull { it.key == member.key }?.inputs.orEmpty().map { it.id }
            val excluded = visible.values.toSet() + progress.values +
                supportInputIds.filter { inputId -> inputs[inputId]?.section == "constellations" }
            buffBindings[member.key] = supportInputIds.filterNot { inputId ->
                inputId in excluded || inputs[inputId]?.section == "internal"
            }
        }
        val bindings = completeBindings.values.flatMap { it.entries }.groupBy({ it.key }, { it.value })
        val context = members.filter { it.key in team }.let { active ->
            active.groupingBy { it.element.lowercase() }.eachCount().mapKeys { "${it.key}Count" }
                .mapValues { it.value.toDouble() } + mapOf(
                "moonsignLevel" to active.sumOf { it.moonsignContribution }.coerceAtMost(2).toDouble(),
                "teamSize" to active.size.toDouble(),
                "hydroCryoCount" to active.count { it.element.equals("Hydro", true) || it.element.equals("Cryo", true) }.toDouble(),
            )
        }
        // Shared rules target logical stats; bind them to every selected character's input IDs.
        val sharedEffects = rules.effects.map { effect -> effect.copy(statChanges = effect.statChanges.flatMap { change ->
            if (change.stat.startsWith("team:")) bindings[change.stat.removePrefix("team:")].orEmpty()
                .map { change.copy(stat = it) } else listOf(change)
        }) }
        val resonances = mutableListOf<DamageEffectV2>()
        for ((element, stat, baseStat, source) in listOf(
            listOf("pyro", "attack", "baseAttack", "baseAttack"),
            listOf("hydro", "maxHp", "baseHp", "baseHp"),
        )) {
            if (team.size < 4 || context.getOrDefault("${element}Count", 0.0) < 2.0) continue
            val changes = selectedMembers.map { member ->
                val id = if (member.key == base.characterKey && baseStat in inputs) baseStat
                    else "resonance.${member.key}.$baseStat"
                inputs[id] = inputs[id]?.copy(section = "build") ?: DamageFormulaInputV2(
                    id, "${member.name} Base $stat", 0.0, 0.0, 100000.0, 1.0,
                    initialValueSource = DamageFormulaInitialValueSourceV2(source, member.key), section = "build")
                StatChangeV2(completeBindings.getValue(member.key).getValue(stat), "flatAdd",
                    FormulaExpressionV2("multiply", operands = listOf(FormulaExpressionV2("input", input = id),
                        FormulaExpressionV2("constant", value = 0.25))))
            }
            resonances += DamageEffectV2("${element}Resonance", "$element resonance", "Team", "25% bonus based on base stats.",
                category = "team", activation = EffectActivationV2("teamComposition", conditions = listOf(
                    EffectActivationConditionV2("contextAtLeast", "teamSize", 4.0),
                    EffectActivationConditionV2("contextAtLeast", "${element}Count", 2.0))), statChanges = changes)
        }
        inputs["cryoResonanceActive"] = DamageFormulaInputV2("cryoResonanceActive", "Enemy affected by Cryo or Frozen", 0.0, 0.0, 1.0, 1.0, section = "combat")
        resonances += DamageEffectV2("cryoResonance", "Cryo resonance", "Team", "15% CRIT Rate against Cryo-affected or Frozen enemies.",
            category = "team", activation = EffectActivationV2("teamComposition", conditions = listOf(
                EffectActivationConditionV2("contextAtLeast", "teamSize", 4.0),
                EffectActivationConditionV2("contextAtLeast", "cryoCount", 2.0),
                EffectActivationConditionV2("inputAtLeast", "cryoResonanceActive", 1.0))),
            formulaModifiers = listOf(FormulaModifierV2(FormulaTargetsV2(damageKinds = listOf("talent")),
                crit = CritContributionV2(additionalRate = FormulaContributionV2(FormulaExpressionV2("constant", value = 15.0))))))
        return base.copy(
            inputs = inputs.values.toList(),
            talents = carry.talents,
            damageTypes = carry.damageTypes,
            statBindings = carry.statBindings,
            effects = carry.effects + selected.flatMap { it.effects } + sharedEffects + resonances,
            teamMembers = members,
            selectedTeam = team,
            teamContext = context + boundContext,
            teamStatInputs = bindings,
            supportProfiles = selected,
            characterStatInputs = visibleBindings,
            characterProgressInputs = progressBindings,
            characterBuffInputs = buffBindings,
        )
    }

    private data class StandardStat(
        val label: String,
        val defaultValue: Double,
        val min: Double,
        val max: Double,
        val step: Double,
        val unit: String = "number",
        val valueKey: String? = null,
    ) {
        fun source(member: DamageTeamMemberV2): String? = when (valueKey) {
            "elementalDamageBonus" -> "stat:${member.element.lowercase()}_dmg_"
            else -> valueKey
        }

        fun input(member: DamageTeamMemberV2, id: String) = DamageFormulaInputV2(
            id = id,
            label = if (valueKey == "elementalDamageBonus") "${member.element} DMG Bonus" else label,
            defaultValue = defaultValue,
            min = min,
            max = max,
            step = step,
            unit = unit,
            initialValueSource = source(member)?.let { DamageFormulaInitialValueSourceV2(it, member.key) },
            section = "stats:${member.key}",
        )
    }

    private data class ProgressInput(
        val label: String,
        val valueKey: String,
        val max: Double,
    ) {
        fun input(member: DamageTeamMemberV2, id: String) = DamageFormulaInputV2(
            id = id,
            label = label,
            defaultValue = if (valueKey == "level") 90.0 else 1.0,
            min = 1.0,
            max = max,
            step = 1.0,
            initialValueSource = DamageFormulaInitialValueSourceV2(valueKey, member.key),
            section = "progress:${member.key}",
        )
    }

    private companion object {
        val STANDARD_STATS = linkedMapOf(
            "maxHp" to StandardStat("Max HP", 0.0, 0.0, 200000.0, 1.0, valueKey = "totalHp"),
            "attack" to StandardStat("Total ATK", 0.0, 0.0, 20000.0, 1.0, valueKey = "totalAttack"),
            "defense" to StandardStat("Total DEF", 0.0, 0.0, 20000.0, 1.0, valueKey = "totalDefense"),
            "elementalMastery" to StandardStat("Elemental Mastery", 0.0, 0.0, 5000.0, 1.0, valueKey = "stat:eleMas"),
            "critRate" to StandardStat("CRIT Rate", 5.0, 0.0, 1000.0, 0.1, "percentPoints", "stat:critRate_"),
            "critDamage" to StandardStat("CRIT DMG", 50.0, 0.0, 1000.0, 0.1, "percentPoints", "stat:critDMG_"),
            "energyRecharge" to StandardStat("Energy Recharge", 100.0, 0.0, 1000.0, 0.1, "percentPoints", "stat:enerRech_"),
            "elementalDamageBonus" to StandardStat("Elemental DMG Bonus", 0.0, 0.0, 1000.0, 0.1, "percentPoints", "elementalDamageBonus"),
            "physicalDamageBonus" to StandardStat("Physical DMG Bonus", 0.0, 0.0, 1000.0, 0.1, "percentPoints", "stat:physical_dmg_"),
        )
        val PROGRESS_INPUTS = linkedMapOf(
            "characterLevel" to ProgressInput("Character level", "level", 90.0),
            "normalTalentLevel" to ProgressInput("Normal Attack level", "normalTalent", 15.0),
            "skillTalentLevel" to ProgressInput("Elemental Skill level", "skillTalent", 15.0),
            "burstTalentLevel" to ProgressInput("Elemental Burst level", "burstTalent", 15.0),
        )
        val REACTION_DAMAGE_ELEMENTS = listOf("Pyro", "Hydro", "Electro", "Cryo", "Dendro", "Physical")

        fun reactionResistanceInput(element: String): String =
            "enemyReaction${element.lowercase().replaceFirstChar(Char::uppercase)}Resistance"
    }
}
