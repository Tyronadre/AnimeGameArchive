package de.tyro.genshinapp.service.damage

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Service
class DamageCalculatorService(
    private val objectMapper: ObjectMapper,
) {
    private val profiles = ConcurrentHashMap<String, DamageCalculatorProfile>()

    fun supports(characterKey: String): Boolean =
        ClassPathResource(profileResource(characterKey)).exists()

    fun find(characterKey: String): DamageCalculatorProfile? {
        val normalizedKey = characterKey.trim().lowercase()
        if (!supports(normalizedKey)) return null
        return profiles.computeIfAbsent(normalizedKey, ::loadProfile)
    }

    fun defaultValues(profile: DamageCalculatorProfile): Map<String, Double> {
        val inputDefaults = profile.inputSections
            .flatMap(DamageInputSection::inputs)
            .associate { input -> input.id to input.defaultValue }
        val effectDefaults = profile.effectSections
            .flatMap(DamageEffectSection::effects)
            .associate { effect ->
                val owned = effect.ownershipRequirements.all { (input, minimum) ->
                    inputDefaults.getOrDefault(input, 0.0) >= minimum
                }
                effect.id to if (effect.defaultEnabled && owned) 1.0 else 0.0
            }
        return inputDefaults + effectDefaults
    }

    fun calculate(
        profile: DamageCalculatorProfile,
        requestedValues: Map<String, Double>,
        teamKeys: List<String> = profile.defaultTeam,
    ): DamageCalculation {
        val inputs = normalizeInputs(profile, requestedValues)
        val effects = profile.effectSections.flatMap(DamageEffectSection::effects)
        val effectById = effects.associateBy(DamageEffectDefinition::id)
        val normalizedTeamKeys = teamKeys.map(String::lowercase).toSet()
        val moonsignLevel = profile.teamMembers
            .filter { member -> member.key.lowercase() in normalizedTeamKeys }
            .sumOf(DamageTeamMemberDefinition::moonsignContribution)
        val activeEffectIds = effects
            .filter { effect ->
                effectIsActive(
                    effect,
                    inputs,
                    effectById,
                    normalizedTeamKeys,
                    moonsignLevel,
                )
            }
            .map(DamageEffectDefinition::id)
            .toSet()
        val effectiveStats = inputs.toMutableMap()
        profile.statModifiers.forEach { modifier ->
            val triggerValue = inputs[modifier.input] ?: 0.0
            when (modifier.operation) {
                "flatAdd" -> effectiveStats[modifier.stat] =
                    (effectiveStats[modifier.stat] ?: 0.0) + modifier.amount * triggerValue
                "percentAdd" -> effectiveStats[modifier.stat] =
                    (effectiveStats[modifier.stat] ?: 0.0) * (1.0 + modifier.amount * triggerValue)
                else -> error("Unsupported stat modifier operation '${modifier.operation}'")
            }
        }
        activeEffectModifiers(profile, activeEffectIds, "stat").forEach { modifier ->
            val stat = requireNotNull(modifier.stat) {
                "Stat effect modifier is missing its stat"
            }
            val value = effectModifierValue(modifier, inputs, effectiveStats)
            when (modifier.operation) {
                "flatAdd", "inputScaled", "thresholdScaled", "thresholdFlatAdd" -> effectiveStats[stat] =
                    (effectiveStats[stat] ?: 0.0) + value
                "percentAdd" -> effectiveStats[stat] =
                    (effectiveStats[stat] ?: 0.0) * (1.0 + value / 100.0)
                else -> error("Unsupported stat effect operation '${modifier.operation}'")
            }
        }

        val formula = profile.formula
        val characterLevel = inputs.getValue(formula.characterLevelInput)
        val enemyLevel = inputs.getValue(formula.enemyLevelInput)
        val defenseReduction = inputs.getValue(formula.defenseReductionInput) / 100.0
        val defenseIgnore = inputs.getValue(formula.defenseIgnoreInput) / 100.0
        val defenseMultiplier = (characterLevel + 100.0) /
            (
                characterLevel + 100.0 +
                    (enemyLevel + 100.0) * (1.0 - defenseReduction) * (1.0 - defenseIgnore)
                )
        val globalResistanceReduction = activeEffectModifiers(
            profile,
            activeEffectIds,
            "resistanceReduction",
        ).sumOf { effectModifierValue(it, inputs, effectiveStats) }
        val resistanceMultiplier = resistanceMultiplier(
            (inputs.getValue(formula.resistanceInput) - globalResistanceReduction) / 100.0,
        )
        val critRate = (effectiveStats.getValue(formula.critRateInput) / 100.0).coerceIn(0.0, 1.0)
        val critDamage = (effectiveStats.getValue(formula.critDamageInput) / 100.0).coerceAtLeast(0.0)

        val results = profile.talents.flatMap { talent ->
            val levelIndex = (effectiveStats.getValue(talent.levelInput).roundToInt() - 1)
                .coerceIn(0, talent.maxLevel - 1)
            talent.instances.map { instance ->
                val baseDamagePerHit = instance.terms.sumOf { term ->
                    effectiveStats.getValue(term.stat) * term.values[levelIndex]
                }
                val instanceDamageBonus = instance.bonusTerms.sumOf { term ->
                    effectiveStats.getValue(term.input) * term.values[levelIndex]
                }
                val rawBaseDamage = baseDamagePerHit * instance.hitCount
                val scopedModifiers = activeEffectModifiers(
                    profile,
                    activeEffectIds,
                    talentKey = talent.key,
                    instance = instance,
                )
                val baseDamageBonus = scopedModifiers
                    .filter { it.layer == "baseDamageBonus" }
                    .sumOf { effectModifierValue(it, inputs, effectiveStats) } / 100.0
                val effectDamageBonus = scopedModifiers
                    .filter { it.layer == "damageBonus" }
                    .sumOf { effectModifierValue(it, inputs, effectiveStats) } / 100.0
                val flatBaseDamage = scopedModifiers
                    .filter { it.layer == "flatBaseDamage" }
                    .sumOf { effectModifierValue(it, inputs, effectiveStats) }
                val elevation = scopedModifiers
                    .filter { it.layer == "elevation" }
                    .sumOf { effectModifierValue(it, inputs, effectiveStats) } / 100.0
                val resistanceReduction = scopedModifiers
                    .filter { it.layer == "resistanceReduction" }
                    .sumOf { effectModifierValue(it, inputs, effectiveStats) }
                val instanceResistanceMultiplier = resistanceMultiplier(
                    (inputs.getValue(formula.resistanceInput) - resistanceReduction) / 100.0,
                )
                val instanceCritRate = (
                    critRate + scopedModifiers
                        .filter { it.layer == "critRate" }
                        .sumOf { effectModifierValue(it, inputs, effectiveStats) } / 100.0
                    ).coerceIn(0.0, 1.0)
                val instanceCritDamage = (
                    critDamage + scopedModifiers
                        .filter { it.layer == "critDamage" }
                        .sumOf { effectModifierValue(it, inputs, effectiveStats) } / 100.0
                    ).coerceAtLeast(0.0)
                val configuredDamageBonus = formula.damageBonusInputs
                    .filter { bonus -> bonus.appliesTo(instance.damageModel) }
                    .sumOf { bonus -> effectiveStats.getValue(bonus.input) / 100.0 }
                val lunarEmBonus = if (instance.damageModel == "lunarReaction") {
                    val elementalMastery = effectiveStats.getValue("elementalMastery")
                    6.0 * elementalMastery / (elementalMastery + 2000.0)
                } else {
                    0.0
                }
                val appliedDefenseMultiplier = if (instance.damageModel == "lunarReaction") {
                    1.0
                } else {
                    defenseMultiplier
                }
                val totalBaseDamage = (rawBaseDamage + flatBaseDamage) * (1.0 + baseDamageBonus)
                val nonCritical = totalBaseDamage *
                    (1.0 + configuredDamageBonus + effectDamageBonus + instanceDamageBonus + lunarEmBonus) *
                    appliedDefenseMultiplier * instanceResistanceMultiplier * (1.0 + elevation)
                val critical = nonCritical * (1.0 + instanceCritDamage)
                val average = nonCritical * (1.0 + instanceCritRate * instanceCritDamage)
                DamageInstanceResult(
                    instanceId = instance.id,
                    nonCritical = nonCritical,
                    critical = critical,
                    average = average,
                    active = instance.requiredEffects.all(activeEffectIds::contains) &&
                        instance.excludedEffects.none(activeEffectIds::contains),
                )
            }
        }

        return DamageCalculation(
            inputs = inputs,
            effectiveStats = effectiveStats,
            activeEffectIds = activeEffectIds,
            defenseMultiplier = defenseMultiplier,
            resistanceMultiplier = resistanceMultiplier,
            results = results,
        )
    }

    private fun normalizeInputs(
        profile: DamageCalculatorProfile,
        requestedValues: Map<String, Double>,
    ): Map<String, Double> {
        val normalizedInputs = profile.inputSections
            .flatMap(DamageInputSection::inputs)
            .associate { input ->
                val value = requestedValues[input.id] ?: input.defaultValue
                input.id to if (input.type == "checkbox") {
                    if (value > 0.0) 1.0 else 0.0
                } else {
                    value.coerceIn(input.min, input.max)
                }
            }
        val normalizedEffects = profile.effectSections
            .flatMap(DamageEffectSection::effects)
            .associate { effect ->
                val default = if (effect.defaultEnabled) 1.0 else 0.0
                effect.id to if ((requestedValues[effect.id] ?: default) > 0.0) 1.0 else 0.0
            }
        return normalizedInputs + normalizedEffects
    }

    private fun loadProfile(key: String): DamageCalculatorProfile {
        val source = ClassPathResource(profileResource(key)).inputStream.use { input ->
            objectMapper.readValue(input, DamageCalculatorProfileSource::class.java)
        }
        require(source.key == key) {
            "Damage profile key '${source.key}' does not match resource key '$key'"
        }
        val characterRoot = ClassPathResource(source.characterDataResource).inputStream.use(
            objectMapper::readTree,
        )
        val talentsRoot = characterRoot.path("talents")
        val talents = source.talents.map { talent ->
            val parameters = talentsRoot.path(talent.sourceTalent)
                .path("attributes")
                .path("parameters")
            require(parameters.isObject) {
                "Talent '${talent.sourceTalent}' is missing for damage profile '$key'"
            }
            val talentLevelCount = parameters.properties().asSequence()
                .map { it.value }
                .firstOrNull { it.isArray && it.size() > 0 }
                ?.size()
                ?: error("Talent '${talent.sourceTalent}' has no multiplier rows for damage profile '$key'")
            val instances = talent.instances.map { instance ->
                DamageInstance(
                    id = instance.id,
                    label = instance.label,
                    hitCount = instance.hitCount,
                    note = instance.note,
                    damageModel = instance.damageModel,
                    tags = instance.tags,
                    requiredEffects = instance.requiredEffects,
                    excludedEffects = instance.excludedEffects,
                    terms = instance.terms.map { term ->
                        DamageScalingTerm(
                            stat = term.stat,
                            statLabel = term.statLabel,
                            values = scalingValues(parameters, term, key, talentLevelCount),
                        )
                    },
                    bonusTerms = instance.bonusTerms.map { term ->
                        DamageBonusTerm(
                            input = term.input,
                            label = term.label,
                            values = parameterValues(parameters, term.parameter, key),
                        )
                    },
                )
            }
            val levelCounts = instances.flatMap { instance ->
                instance.terms.map { it.values.size } + instance.bonusTerms.map { it.values.size }
            }.distinct()
            require(levelCounts.size == 1) {
                "Talent '${talent.sourceTalent}' has inconsistent multiplier level counts: $levelCounts"
            }
            DamageTalent(
                key = talent.key,
                marker = talent.marker,
                kindMessageKey = talent.kindMessageKey,
                name = talent.name,
                levelInput = talent.levelInput,
                maxLevel = levelCounts.single(),
                instances = instances,
            )
        }

        val inputIds = source.inputSections
            .flatMap(DamageInputSection::inputs)
            .map(DamageInputDefinition::id)
            .toSet()
        val effects = source.effectSections.flatMap(DamageEffectSection::effects)
        val effectIds = effects.map(DamageEffectDefinition::id).toSet()
        require(effectIds.size == effects.size) {
            "Damage profile '$key' contains duplicate effect IDs"
        }
        require(effectIds.none(inputIds::contains)) {
            "Damage profile '$key' reuses an input ID for an effect: ${effectIds intersect inputIds}"
        }
        val effectRequirements = effects.flatMap { effect ->
            effect.requiresEffects + effect.modifiers.flatMap(DamageEffectModifierDefinition::requiresEffects)
        }.plus(
            talents.flatMap { talent ->
                talent.instances.flatMap { instance -> instance.requiredEffects + instance.excludedEffects }
            },
        ).toSet()
        require(effectRequirements.all(effectIds::contains)) {
            "Damage profile '$key' references undefined effects: ${effectRequirements - effectIds}"
        }
        require(effects.none { effect -> effect.id in effect.requiresEffects }) {
            "Damage profile '$key' contains a self-dependent effect"
        }
        val teamMemberKeys = source.teamMembers.map { it.key.lowercase() }.toSet()
        require(source.defaultTeam.map(String::lowercase).all(teamMemberKeys::contains)) {
            "Damage profile '$key' has default team members outside its candidate list"
        }
        val requiredTeamMembers = effects.flatMap(DamageEffectDefinition::requiredTeamMembers)
            .map(String::lowercase)
            .toSet()
        require(requiredTeamMembers.all(teamMemberKeys::contains)) {
            "Damage profile '$key' references undefined team members: ${requiredTeamMembers - teamMemberKeys}"
        }
        val referencedInputs = buildSet {
            add(source.formula.characterLevelInput)
            add(source.formula.enemyLevelInput)
            add(source.formula.resistanceInput)
            add(source.formula.defenseReductionInput)
            add(source.formula.defenseIgnoreInput)
            add(source.formula.critRateInput)
            add(source.formula.critDamageInput)
            addAll(source.formula.damageBonusInputs.map(DamageBonusInputDefinition::input))
            addAll(source.statModifiers.map(StatModifierDefinition::input))
            addAll(source.statModifiers.map(StatModifierDefinition::stat))
            effects.forEach { effect ->
                addAll(effect.ownershipRequirements.keys)
                effect.modifiers.forEach { modifier ->
                    modifier.input?.let(::add)
                    modifier.secondaryInput?.let(::add)
                    modifier.levelInput?.let(::add)
                    modifier.stat?.let(::add)
                }
            }
            talents.forEach { talent ->
                add(talent.levelInput)
                talent.instances.forEach { instance ->
                    addAll(instance.terms.map(DamageScalingTerm::stat))
                    addAll(instance.bonusTerms.map(DamageBonusTerm::input))
                }
            }
        }
        require(referencedInputs.all(inputIds::contains)) {
            "Damage profile '$key' references undefined inputs: ${referencedInputs - inputIds}"
        }

        return DamageCalculatorProfile(
            key = source.key,
            characterKey = source.characterKey,
            name = source.name,
            element = source.element,
            dataVersion = characterRoot.path("version").asText(),
            scopeMessageKey = source.scopeMessageKey,
            inputSections = source.inputSections,
            statModifiers = source.statModifiers,
            teamMembers = source.teamMembers,
            defaultTeam = source.defaultTeam,
            effectSections = source.effectSections,
            formula = source.formula,
            talents = talents,
        )
    }

    private fun parameterValues(
        parameters: JsonNode,
        parameter: String,
        profileKey: String,
    ): List<Double> {
        val values = parameters.path(parameter)
        require(values.isArray && values.size() > 0) {
            "Parameter '$parameter' is missing for damage profile '$profileKey'"
        }
        return values.map(JsonNode::asDouble)
    }

    private fun scalingValues(
        parameters: JsonNode,
        source: DamageScalingSource,
        profileKey: String,
        talentLevelCount: Int,
    ): List<Double> {
        if (source.values.isEmpty()) {
            return parameterValues(
                parameters,
                requireNotNull(source.parameter) {
                    "Scaling term '${source.stat}' requires either parameter or values"
                },
                profileKey,
            )
        }
        return when (source.values.size) {
            1 -> List(talentLevelCount) { source.values.single() }
            talentLevelCount -> source.values
            else -> error(
                "Fixed scaling term '${source.stat}' has ${source.values.size} values; " +
                    "expected 1 or $talentLevelCount for damage profile '$profileKey'",
            )
        }
    }

    private fun resistanceMultiplier(resistance: Double): Double = when {
        resistance < 0.0 -> 1.0 - resistance / 2.0
        resistance < 0.75 -> 1.0 - resistance
        else -> 1.0 / (4.0 * resistance + 1.0)
    }

    private fun effectIsActive(
        effect: DamageEffectDefinition,
        inputs: Map<String, Double>,
        effectById: Map<String, DamageEffectDefinition>,
        teamKeys: Set<String>,
        moonsignLevel: Int,
        visiting: Set<String> = emptySet(),
    ): Boolean {
        if (inputs.getOrDefault(effect.id, 0.0) <= 0.0 || effect.id in visiting) return false
        if (effect.requiredTeamMembers.any { it.lowercase() !in teamKeys }) return false
        if (moonsignLevel < effect.minimumMoonsignLevel) return false
        return effect.requiresEffects.all { requirement ->
            effectById[requirement]?.let { dependency ->
                effectIsActive(
                    dependency,
                    inputs,
                    effectById,
                    teamKeys,
                    moonsignLevel,
                    visiting + effect.id,
                )
            } ?: false
        }
    }

    private fun activeEffectModifiers(
        profile: DamageCalculatorProfile,
        activeEffectIds: Set<String>,
        layer: String? = null,
        talentKey: String? = null,
        instance: DamageInstance? = null,
    ): List<DamageEffectModifierDefinition> = profile.effectSections
        .flatMap(DamageEffectSection::effects)
        .filter { it.id in activeEffectIds }
        .flatMap { effect -> effect.modifiers.map { modifier -> modifier.copy(sourceEffectId = effect.id) } }
        .filter { modifier -> layer == null || modifier.layer == layer }
        .filter { modifier -> modifier.requiresEffects.all(activeEffectIds::contains) }
        .filter { modifier -> modifier.talentKeys.isEmpty() || talentKey != null && talentKey in modifier.talentKeys }
        .filter { modifier -> modifier.instanceIds.isEmpty() || instance != null && instance.id in modifier.instanceIds }
        .filter { modifier -> modifier.requiredTags.isEmpty() || instance != null && instance.tags.containsAll(modifier.requiredTags) }

    private fun effectModifierValue(
        modifier: DamageEffectModifierDefinition,
        inputs: Map<String, Double>,
        effectiveStats: Map<String, Double>,
    ): Double {
        val inputValue = modifier.input?.let { input ->
            effectiveStats[input] ?: inputs.getValue(input)
        } ?: 0.0
        val rawValue = when (modifier.operation) {
            "flatAdd", "percentAdd" -> modifier.amount
            "inputScaled" -> inputValue * modifier.amount
            "inputProductScaled" -> inputValue *
                (modifier.secondaryInput?.let { effectiveStats[it] ?: inputs.getValue(it) } ?: 0.0) *
                modifier.amount
            "thresholdScaled" -> (inputValue - modifier.threshold).coerceAtLeast(0.0) * modifier.amount
            "thresholdFlatAdd" -> if (inputValue >= modifier.threshold) modifier.amount else 0.0
            "levelValue" -> modifier.levelValue(effectiveStats)
            "levelValueScaled" -> inputValue * modifier.levelValue(effectiveStats)
            else -> error("Unsupported effect modifier operation '${modifier.operation}'")
        }
        return modifier.max?.let(rawValue::coerceAtMost) ?: rawValue
    }

    private fun profileResource(key: String): String =
        "data/damage-calculators/${key.trim().lowercase()}.json"

    private fun DamageEffectModifierDefinition.levelValue(inputs: Map<String, Double>): Double {
        require(values.isNotEmpty()) { "Effect modifier '$operation' requires values" }
        val levelKey = requireNotNull(levelInput) { "Effect modifier '$operation' requires levelInput" }
        val levelIndex = (inputs.getValue(levelKey).roundToInt() - 1).coerceIn(0, values.lastIndex)
        return values[levelIndex]
    }
}

data class DamageCalculatorProfileSource(
    val key: String,
    val characterKey: String,
    val name: String,
    val element: String,
    val characterDataResource: String,
    val scopeMessageKey: String,
    val inputSections: List<DamageInputSection>,
    val statModifiers: List<StatModifierDefinition> = emptyList(),
    val teamMembers: List<DamageTeamMemberDefinition> = emptyList(),
    val defaultTeam: List<String> = emptyList(),
    val effectSections: List<DamageEffectSection> = emptyList(),
    val formula: DamageFormulaDefinition,
    val talents: List<DamageTalentSource>,
)

data class DamageCalculatorProfile(
    val key: String,
    val characterKey: String,
    val name: String,
    val element: String,
    val dataVersion: String,
    val scopeMessageKey: String,
    val inputSections: List<DamageInputSection>,
    val statModifiers: List<StatModifierDefinition>,
    val teamMembers: List<DamageTeamMemberDefinition>,
    val defaultTeam: List<String>,
    val effectSections: List<DamageEffectSection>,
    val formula: DamageFormulaDefinition,
    val talents: List<DamageTalent>,
)

data class DamageInputSection(
    val key: String,
    val messageKey: String,
    val inputs: List<DamageInputDefinition>,
)

data class DamageInputDefinition(
    val id: String,
    val messageKey: String,
    val formulaLabel: String? = null,
    val type: String = "range",
    val min: Double = 0.0,
    val max: Double = 1.0,
    val step: Double = 1.0,
    val defaultValue: Double = 0.0,
    val suffix: String = "",
    val helpMessageKey: String? = null,
    val requiredTeamMember: String? = null,
    val initialValueSource: DamageInitialValueSource? = null,
    val currentValueLoaded: Boolean = false,
)

data class DamageInitialValueSource(
    val characterKey: String? = null,
    val valueKey: String,
)

data class DamageTeamMemberDefinition(
    val key: String,
    val name: String,
    val element: String,
    val moonsignContribution: Int = 0,
    val locked: Boolean = false,
)

data class StatModifierDefinition(
    val stat: String,
    val operation: String,
    val input: String,
    val amount: Double,
    val label: String? = null,
)

data class DamageEffectSection(
    val key: String,
    val messageKey: String,
    val helpMessageKey: String? = null,
    val defaultExpanded: Boolean = false,
    val effects: List<DamageEffectDefinition>,
)

data class DamageEffectDefinition(
    val id: String,
    val nameMessageKey: String,
    val descriptionMessageKey: String,
    val sourceMessageKey: String? = null,
    val impactMessageKey: String? = null,
    val defaultEnabled: Boolean = true,
    val requiresEffects: List<String> = emptyList(),
    val requiredTeamMembers: List<String> = emptyList(),
    val minimumMoonsignLevel: Int = 0,
    val ownershipRequirements: Map<String, Double> = emptyMap(),
    val modifiers: List<DamageEffectModifierDefinition> = emptyList(),
)

data class DamageEffectModifierDefinition(
    val layer: String,
    val operation: String,
    val stat: String? = null,
    val input: String? = null,
    val secondaryInput: String? = null,
    val amount: Double,
    val threshold: Double = 0.0,
    val max: Double? = null,
    val requiredTags: List<String> = emptyList(),
    val talentKeys: List<String> = emptyList(),
    val instanceIds: List<String> = emptyList(),
    val requiresEffects: List<String> = emptyList(),
    val displayMessageKey: String? = null,
    val displayFormat: String = "percent",
    val levelInput: String? = null,
    val values: List<Double> = emptyList(),
    val sourceEffectId: String? = null,
)

data class DamageFormulaDefinition(
    val characterLevelInput: String,
    val enemyLevelInput: String,
    val resistanceInput: String,
    val defenseReductionInput: String,
    val defenseIgnoreInput: String,
    val damageBonusInputs: List<DamageBonusInputDefinition>,
    val critRateInput: String,
    val critDamageInput: String,
)

data class DamageBonusInputDefinition(
    val input: String,
    val includedDamageModels: List<String> = emptyList(),
    val excludedDamageModels: List<String> = emptyList(),
) {
    fun appliesTo(damageModel: String): Boolean =
        (includedDamageModels.isEmpty() || damageModel in includedDamageModels) &&
            damageModel !in excludedDamageModels
}

data class DamageTalentSource(
    val key: String,
    val marker: String,
    val kindMessageKey: String,
    val name: String,
    val levelInput: String,
    val sourceTalent: String,
    val instances: List<DamageInstanceSource>,
)

data class DamageInstanceSource(
    val id: String,
    val label: String,
    val hitCount: Int = 1,
    val note: String? = null,
    val damageModel: String = "direct",
    val tags: List<String> = emptyList(),
    val requiredEffects: List<String> = emptyList(),
    val excludedEffects: List<String> = emptyList(),
    val terms: List<DamageScalingSource>,
    val bonusTerms: List<DamageBonusSource> = emptyList(),
)

data class DamageScalingSource(
    val stat: String,
    val statLabel: String,
    val parameter: String? = null,
    val values: List<Double> = emptyList(),
)

data class DamageBonusSource(
    val input: String,
    val label: String,
    val parameter: String,
)

data class DamageTalent(
    val key: String,
    val marker: String,
    val kindMessageKey: String,
    val name: String,
    val levelInput: String,
    val maxLevel: Int,
    val instances: List<DamageInstance>,
)

data class DamageInstance(
    val id: String,
    val label: String,
    val hitCount: Int,
    val note: String?,
    val damageModel: String,
    val tags: List<String>,
    val requiredEffects: List<String>,
    val excludedEffects: List<String>,
    val terms: List<DamageScalingTerm>,
    val bonusTerms: List<DamageBonusTerm>,
)

data class DamageScalingTerm(
    val stat: String,
    val statLabel: String,
    val values: List<Double>,
)

data class DamageBonusTerm(
    val input: String,
    val label: String,
    val values: List<Double>,
)

data class DamageCalculation(
    val inputs: Map<String, Double>,
    val effectiveStats: Map<String, Double>,
    val activeEffectIds: Set<String>,
    val defenseMultiplier: Double,
    val resistanceMultiplier: Double,
    val results: List<DamageInstanceResult>,
) {
    val resultsById: Map<String, DamageInstanceResult>
        get() = results.associateBy(DamageInstanceResult::instanceId)
}

data class DamageInstanceResult(
    val instanceId: String,
    val nonCritical: Double,
    val critical: Double,
    val average: Double,
    val active: Boolean = true,
) {
    val roundedNonCritical: Long
        get() = nonCritical.roundToLong()

    val roundedCritical: Long
        get() = critical.roundToLong()

    val roundedAverage: Long
        get() = average.roundToLong()
}
