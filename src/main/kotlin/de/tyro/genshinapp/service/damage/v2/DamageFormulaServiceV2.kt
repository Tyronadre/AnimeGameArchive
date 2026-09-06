package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

@Service
class DamageFormulaServiceV2(
    private val objectMapper: ObjectMapper,
) {
    private val profiles = ConcurrentHashMap<String, LoadedDamageProfileV2>()
    private val supportRoots = ConcurrentHashMap<String, JsonNode>()
    private val reactionCatalog by lazy {
        ClassPathResource(REACTION_RESOURCE).inputStream.use { input ->
            objectMapper.readValue(input, DamageReactionCatalogV2::class.java)
        }.also { catalog ->
            require(catalog.schemaVersion == 2) { "Unsupported reaction schema" }
            require(catalog.characterLevelMultipliers.size >= 90) { "Missing reaction level multipliers" }
            require(catalog.crystallizeLevelMultipliers.size >= 90) { "Missing Crystallize level multipliers" }
            require(catalog.reactions.map { it.key }.distinct().size == catalog.reactions.size) {
                "Duplicate directional reaction keys"
            }
            catalog.reactions.forEach { reaction ->
                require(reaction.kind in setOf("amplifying", "additive", "transformative", "utility", "shield")) {
                    "Unsupported reaction kind '${reaction.kind}'"
                }
                require(reaction.triggerElements.isNotEmpty())
                require(reaction.requiredTeamElements.isNotEmpty())
                require(reaction.baseMultiplier >= 0.0)
                require(reaction.elementalMasteryCoefficient >= 0.0)
                require(reaction.elementalMasteryDenominator > 0.0)
            }
        }
    }

    fun supports(characterKey: String): Boolean =
        ClassPathResource(profileResource(characterKey)).exists()

    fun profile(characterKey: String): DamageFormulaProfileV2? =
        loadedProfile(characterKey)?.source

    fun document(characterKey: String): DamageFormulaDocumentV2? {
        val profile = profile(characterKey) ?: return null
        return document(profile)
    }

    fun document(profile: DamageFormulaProfileV2): DamageFormulaDocumentV2 =
        DamageFormulaDocumentV2(
            profile = profile,
            defaultCalculation = calculate(profile, DamageCalculationRequestV2()),
        )

    fun calculate(
        profile: DamageFormulaProfileV2,
        request: DamageCalculationRequestV2,
    ): DamageCalculationV2 {
        val loaded = requireNotNull(loadedProfile(profile.key)) {
            "Damage formula profile '${profile.key}' is not available"
        }
        require(profile.characterKey == loaded.source.characterKey) {
            "Damage formula profile '${profile.key}' changed its character key"
        }
        val composed = loaded.copy(source = profile, supportRoots = profile.supportProfiles.mapNotNull { support ->
            support.characterDataResource?.let { resource -> support.key to supportRoots.computeIfAbsent(resource) {
                ClassPathResource(resource).inputStream.use(objectMapper::readTree)
            } }
        }.toMap())
        validate(composed)
        return calculate(composed, request)
    }

    fun calculate(
        characterKey: String,
        request: DamageCalculationRequestV2,
    ): DamageCalculationV2? = loadedProfile(characterKey)?.let { loaded ->
        calculate(loaded, request)
    }

    private fun calculate(
        loaded: LoadedDamageProfileV2,
        request: DamageCalculationRequestV2,
    ): DamageCalculationV2 {
        val profile = loaded.source
        val inputs = profile.inputs.associate { definition ->
            definition.id to (request.inputs[definition.id] ?: definition.defaultValue)
                .coerceIn(definition.min, definition.max)
        }
        val enabledEffects = profile.effects.filter { effect ->
            val enabled = request.enabledEffects[effect.id] ?: if (effect.ownershipRequirements.isNotEmpty()) {
                effect.ownershipRequirements.all { (id, minimum) -> inputs.getValue(id) >= minimum }
            } else effect.defaultEnabled
            enabled && effect.activation.conditions.all { condition ->
                activationConditionIsSatisfied(condition, inputs, profile)
            }
        }
        var activeEffectIds = emptySet<String>()
        // Resolve prerequisites and non-stacking buffs together. Suppressed copies must
        // not activate dependent effects; strongest eligible copy wins deterministically.
        var settled = false
        for (iteration in 0..enabledEffects.size + 1) {
            val next = enabledEffects.filter { it.requiresEffects.all(activeEffectIds::contains) }
                .groupBy { it.stackingGroup?.let { group -> "group:$group" } ?: "effect:${it.id}" }
                .values.map { candidates -> candidates.maxWith(compareBy<DamageEffectV2> {
                    it.stackingPriority?.let { priority -> evaluate(priority, inputs, loaded).value } ?: 0.0
                }.thenBy { it.id }) }.mapTo(linkedSetOf()) { it.id }
            if (next == activeEffectIds) { settled = true; break }
            activeEffectIds = next
        }
        require(settled) { "Effect dependencies and stacking rules did not converge" }
        val activeEffects = enabledEffects.filter { it.id in activeEffectIds }
        val effectiveStats = inputs.toMutableMap()
        val resolvedChanges = mutableListOf<ResolvedStatChangeV2>()
        // A phase reads an immutable snapshot: converted stats cannot recursively feed
        // each other, and JSON/team ordering cannot change the calculated amounts.
        activeEffects.flatMap { effect -> effect.statChanges.map { effect to it } }
            .groupBy { (_, change) -> change.phase }.toSortedMap().forEach { (_, changes) ->
            val phaseStats = effectiveStats.toMap()
            changes.forEach changeLoop@{ (effect, change) ->
                if (!change.conditions.all { condition ->
                        activationConditionIsSatisfied(condition, inputs, profile)
                    } || !change.requiresEffects.all(activeEffectIds::contains)
                ) {
                    return@changeLoop
                }
                val evaluation = evaluate(change.value, phaseStats, loaded)
                val amount = evaluation.value
                val currentValue = effectiveStats.getValue(change.stat)
                val changedValue = when (change.operation) {
                    "flatAdd" -> currentValue + amount
                    "percentAdd" -> currentValue + phaseStats.getValue(change.stat) * amount / 100.0
                    else -> error("Unsupported stat operation '${change.operation}' in '${effect.id}'")
                }
                effectiveStats[change.stat] = changedValue
                    .let { value -> change.minimum?.let(value::coerceAtLeast) ?: value }
                    .let { value -> change.maximum?.let(value::coerceAtMost) ?: value }
                resolvedChanges += ResolvedStatChangeV2(effect.id, change.stat,
                    effectiveStats.getValue(change.stat) - currentValue, evaluation.references, change.value)
            }
        }

        val damageTypes = profile.damageTypes.associateBy(DamageTypeDefinitionV2::id)
        val results = profile.talents.flatMap { talent ->
            talent.formulas
                .filter { formula ->
                    formula.requiredEffects.all(activeEffectIds::contains) &&
                        formula.excludedEffects.none(activeEffectIds::contains)
                }
                .map { formula ->
                calculateFormula(
                    talent = talent,
                    formula = formula,
                    damageType = damageTypes.getValue(formula.damageType),
                    inputs = inputs,
                    effectiveStats = effectiveStats,
                    activeEffects = activeEffects,
                    loaded = loaded,
                )
                }
        }
        return DamageCalculationV2(
            inputs = inputs,
            effectiveStats = effectiveStats,
            activeEffectIds = activeEffectIds,
            results = results,
            effectMetrics = activeEffects.associate { effect -> effect.id to effect.metrics.map { metric ->
                if (metric.conditions.all { activationConditionIsSatisfied(it, inputs, profile) }) {
                    evaluate(metric.expression, effectiveStats, loaded).let { EvaluatedFormulaPartV2(it.value, it.references) }
                } else EvaluatedFormulaPartV2(0.0, emptyList())
            } },
            statChanges = resolvedChanges,
        )
    }

    private fun calculateFormula(
        talent: DamageTalentV2,
        formula: DamageFormulaV2,
        damageType: DamageTypeDefinitionV2,
        inputs: Map<String, Double>,
        effectiveStats: Map<String, Double>,
        activeEffects: List<DamageEffectV2>,
        loaded: LoadedDamageProfileV2,
    ): DamageFormulaResultV2 {
        val matchedModifiers = activeEffects.flatMap { effect ->
            effect.formulaModifiers
                .filter { modifier -> modifier.targets.matches(formula, damageType) &&
                    modifier.requiresEffects.all { id -> activeEffects.any { it.id == id } } &&
                    modifier.conditions.all { activationConditionIsSatisfied(it, inputs, loaded.source) }
                }
                .mapNotNull { modifier ->
                    if (damageType.independentProc) {
                        modifier.resistanceMitigation?.let {
                            effect to FormulaModifierV2(modifier.targets, resistanceMitigation = it)
                        }
                    } else effect to modifier
                }
        }
        val formulaBaseEvaluation = evaluate(formula.base, effectiveStats, loaded)
        val additiveBaseEvaluations = matchedModifiers.mapNotNull { (_, modifier) ->
            modifier.base?.let { evaluate(it, effectiveStats, loaded) }
        }
        val baseDamageBonusEvaluations = matchedModifiers.mapNotNull { (_, modifier) ->
            modifier.baseDamageBonus?.let { evaluateContribution(it, effectiveStats, loaded) }
        }
        val additiveBase = additiveBaseEvaluations.sumOf(ExpressionEvaluationV2::value)
        val baseDamageBonus = baseDamageBonusEvaluations.sumOf(ExpressionEvaluationV2::value)
        val baseMultiplier = 1.0 + baseDamageBonus
        val base = (formulaBaseEvaluation.value + additiveBase) * baseMultiplier

        val bonusContributions = buildList {
            formula.damageBonus?.let(::add)
            damageType.innateDamageBonus?.let(::add)
            matchedModifiers.mapNotNullTo(this) { (_, modifier) -> modifier.damageBonus }
        }
        val bonusEvaluations = bonusContributions.map { contribution ->
            evaluateContribution(contribution, effectiveStats, loaded)
        }
        val innateEvaluation = damageType.innateDamageBonus?.let { contribution ->
            evaluateContribution(contribution, effectiveStats, loaded)
        }
        val innateBonus = innateEvaluation?.value ?: 0.0
        val authoredBonus = bonusEvaluations.sumOf(ExpressionEvaluationV2::value) - innateBonus
        val elementalBonus = if (damageType.appliesElementalDamageBonus) {
            effectiveStats.getValue("elementalDamageBonus") / 100.0
        } else {
            0.0
        }
        val otherBonus = if (damageType.appliesOtherDamageBonus) (effectiveStats["otherDamageBonus"] ?: 0.0) / 100.0 else 0.0
        val bonusMultiplier = 1.0 + elementalBonus + otherBonus + innateBonus + authoredBonus

        val elevationEvaluations = matchedModifiers.mapNotNull { (_, modifier) ->
            modifier.elevation?.let { evaluateContribution(it, effectiveStats, loaded) }
        }
        val elevationBonus = elevationEvaluations.sumOf(ExpressionEvaluationV2::value)
        val elevationMultiplier = 1.0 + elevationBonus

        val defenseParts = buildList {
            formula.defenseMitigation?.let(::add)
            matchedModifiers.mapNotNullTo(this) { (_, modifier) -> modifier.defenseMitigation }
        }
        val defenseReductionEvaluations = defenseParts.mapNotNull { part ->
            part.reduction?.let { evaluateContribution(it, effectiveStats, loaded) }
        }
        val defenseIgnoreEvaluations = defenseParts.mapNotNull { part ->
            part.ignore?.let { evaluateContribution(it, effectiveStats, loaded) }
        }
        val defenseReduction = inputs.getValue("defenseReduction") / 100.0 +
            defenseReductionEvaluations.sumOf(ExpressionEvaluationV2::value)
        val defenseIgnore = inputs.getValue("defenseIgnore") / 100.0 +
            defenseIgnoreEvaluations.sumOf(ExpressionEvaluationV2::value)
        val defenseBypassed = damageType.defenseBehavior == "bypass" ||
            defenseParts.any { it.bypass == true }
        val characterLevel = inputs.getValue("characterLevel")
        val enemyLevel = inputs.getValue("enemyLevel")
        val defenseMultiplier = if (defenseBypassed) {
            1.0
        } else {
            (characterLevel + 100.0) /
                (
                    characterLevel + 100.0 +
                        (enemyLevel + 100.0) * (1.0 - defenseReduction) * (1.0 - defenseIgnore)
                    )
        }

        val resistanceParts = buildList {
            formula.resistanceMitigation?.let(::add)
            matchedModifiers.mapNotNullTo(this) { (_, modifier) -> modifier.resistanceMitigation }
        }
        val resistanceReductionEvaluations = resistanceParts.mapNotNull { part ->
            part.reduction?.let { evaluateContribution(it, effectiveStats, loaded) }
        }
        val resistanceReduction = resistanceReductionEvaluations
            .sumOf(ExpressionEvaluationV2::value)
        val effectiveResistance = inputs.getValue(damageType.resistanceInput) / 100.0 - resistanceReduction
        val resistanceMultiplier = resistanceMultiplier(effectiveResistance)

        val critParts = buildList {
            formula.crit?.let(::add)
            matchedModifiers.mapNotNullTo(this) { (_, modifier) -> modifier.crit }
        }
        val additionalRateEvaluations = critParts.mapNotNull { part ->
            part.additionalRate?.let { evaluateContribution(it, effectiveStats, loaded) }
        }
        val additionalDamageEvaluations = critParts.mapNotNull { part ->
            part.additionalDamage?.let { evaluateContribution(it, effectiveStats, loaded) }
        }
        val additionalCritRate = additionalRateEvaluations.sumOf(ExpressionEvaluationV2::value)
        val additionalCritDamage = additionalDamageEvaluations.sumOf(ExpressionEvaluationV2::value)
        val critRate = if (damageType.allowsCriticalHits) {
            (effectiveStats.getValue("critRate") / 100.0 + additionalCritRate).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val critDamage = if (damageType.allowsCriticalHits) {
            (effectiveStats.getValue("critDamage") / 100.0 + additionalCritDamage).coerceAtLeast(0.0)
        } else {
            0.0
        }

        val postBonusEvaluations = matchedModifiers.mapNotNull { (_, modifier) ->
            modifier.postBonusBase?.let { evaluate(it, effectiveStats, loaded) }
        }
        val postBonusBase = postBonusEvaluations.sumOf { it.value }
        val nonCritical = (base * bonusMultiplier + postBonusBase) * defenseMultiplier * resistanceMultiplier *
            elevationMultiplier
        val critical = nonCritical * (1.0 + critDamage)
        val average = nonCritical * (1.0 + critRate * critDamage)
        val reactions = calculateReactions(
            formula = formula,
            damageType = damageType,
            inputs = inputs,
            effectiveStats = effectiveStats,
            activeEffects = activeEffects,
            loaded = loaded,
            nonCritical = nonCritical,
            critical = critical,
            average = average,
            damageBonusMultiplier = bonusMultiplier,
            defenseMultiplier = defenseMultiplier,
            resistanceMultiplier = resistanceMultiplier,
            elevationMultiplier = elevationMultiplier,
            critRate = critRate,
            critDamage = critDamage,
        )
        val inputReferences = listOf(
            ResolvedFormulaReferenceV2("input", "elementalDamageBonus", effectiveStats.getValue("elementalDamageBonus")),
        )
        val defenseReferences = listOf(
            ResolvedFormulaReferenceV2("input", "characterLevel", characterLevel),
            ResolvedFormulaReferenceV2("input", "enemyLevel", enemyLevel),
            ResolvedFormulaReferenceV2("input", "defenseReduction", inputs.getValue("defenseReduction")),
            ResolvedFormulaReferenceV2("input", "defenseIgnore", inputs.getValue("defenseIgnore")),
        )
        val resistanceReferences = listOf(
            ResolvedFormulaReferenceV2("input", damageType.resistanceInput, inputs.getValue(damageType.resistanceInput)),
        )
        val critReferences = listOf(
            ResolvedFormulaReferenceV2("input", "critRate", effectiveStats.getValue("critRate")),
            ResolvedFormulaReferenceV2("input", "critDamage", effectiveStats.getValue("critDamage")),
        )

        return DamageFormulaResultV2(
            formulaId = formula.id,
            talentKey = talent.key,
            damageType = damageType.id,
            element = damageType.element,
            base = EvaluatedFormulaPartV2(
                value = base,
                references = formulaBaseEvaluation.references +
                    additiveBaseEvaluations.flatMap(ExpressionEvaluationV2::references) +
                    baseDamageBonusEvaluations.flatMap(ExpressionEvaluationV2::references),
                formulaValue = formulaBaseEvaluation.value,
                additiveValue = additiveBase,
                multiplier = baseMultiplier,
            ),
            damageBonus = DamageBonusResultV2(
                elementalBonus = elementalBonus,
                otherBonus = otherBonus,
                innateBonus = innateBonus,
                authoredBonus = authoredBonus,
                multiplier = bonusMultiplier,
                references = inputReferences + bonusEvaluations.flatMap(ExpressionEvaluationV2::references),
            ),
            defenseMitigation = MitigationResultV2(
                reduction = defenseReduction,
                ignore = defenseIgnore,
                bypassed = defenseBypassed,
                multiplier = defenseMultiplier,
                references = defenseReferences +
                    defenseReductionEvaluations.flatMap(ExpressionEvaluationV2::references) +
                    defenseIgnoreEvaluations.flatMap(ExpressionEvaluationV2::references),
            ),
            resistanceMitigation = MitigationResultV2(
                reduction = resistanceReduction,
                multiplier = resistanceMultiplier,
                references = resistanceReferences +
                    resistanceReductionEvaluations.flatMap(ExpressionEvaluationV2::references),
            ),
            crit = CritResultV2(
                additionalRate = additionalCritRate,
                additionalDamage = additionalCritDamage,
                effectiveRate = critRate,
                effectiveDamage = critDamage,
                references = critReferences +
                    additionalRateEvaluations.flatMap(ExpressionEvaluationV2::references) +
                    additionalDamageEvaluations.flatMap(ExpressionEvaluationV2::references),
            ),
            elevation = FormulaMultiplierResultV2(
                authoredBonus = elevationBonus,
                multiplier = elevationMultiplier,
                references = elevationEvaluations.flatMap(ExpressionEvaluationV2::references),
            ),
            nonCritical = nonCritical,
            critical = critical,
            average = average,
            appliedEffectIds = matchedModifiers.mapTo(linkedSetOf()) { (effect) -> effect.id },
            postBonusBase = EvaluatedFormulaPartV2(postBonusBase, postBonusEvaluations.flatMap { it.references }),
            reactions = reactions,
        )
    }

    private fun calculateReactions(
        formula: DamageFormulaV2,
        damageType: DamageTypeDefinitionV2,
        inputs: Map<String, Double>,
        effectiveStats: Map<String, Double>,
        activeEffects: List<DamageEffectV2>,
        loaded: LoadedDamageProfileV2,
        nonCritical: Double,
        critical: Double,
        average: Double,
        damageBonusMultiplier: Double,
        defenseMultiplier: Double,
        resistanceMultiplier: Double,
        elevationMultiplier: Double,
        critRate: Double,
        critDamage: Double,
    ): List<DamageReactionResultV2> {
        if (damageType.kind != "talent" || damageType.independentProc ||
            formula.tags.any { it in setOf("multiHit", "noReaction") }
        ) {
            return emptyList()
        }
        val profile = loaded.source
        val selectedMembers = profile.teamMembers.filter { it.key in profile.selectedTeam }
        val elementalMastery = effectiveStats.getValue("elementalMastery").coerceAtLeast(0.0)
        val characterLevel = inputs.getValue("characterLevel").roundToInt().coerceIn(1, 90)
        return reactionCatalog.reactions.filter { reaction ->
            reaction.triggerElements.any { it.equals(damageType.element, ignoreCase = true) } &&
                reaction.requiredTeamElements.all { requiredElement ->
                    selectedMembers.any { it.element.equals(requiredElement, ignoreCase = true) }
                } &&
                (reaction.requiredFormulaTagsAny.isEmpty() ||
                    reaction.requiredFormulaTagsAny.any(formula.tags::contains))
        }.map { reaction ->
            val outputElement = reaction.damageElement ?: damageType.element
            val reactionDamageType = damageType.copy(
                id = "reaction:${reaction.key}",
                kind = "reaction",
                element = outputElement,
            )
            val reactionFormula = formula.copy(tags = (formula.tags + reaction.id).distinct())
            val matchingReactionModifiers = activeEffects.flatMap { effect ->
                effect.formulaModifiers.filter { modifier ->
                    modifier.targets.matches(reactionFormula, reactionDamageType) &&
                        modifier.requiresEffects.all { id -> activeEffects.any { it.id == id } } &&
                        modifier.conditions.all { activationConditionIsSatisfied(it, inputs, profile) }
                }.map { modifier -> effect to modifier }
            }
            // Reaction bonuses must explicitly target the reaction damage kind. This prevents
            // ordinary all-DMG bonuses from being applied twice to amplifying/additive hits.
            val bonusEvaluations = matchingReactionModifiers.mapNotNull { (effect, modifier) ->
                if ("reaction" in modifier.targets.damageKinds) {
                    modifier.damageBonus?.let { bonus ->
                        Triple(effect, modifier, evaluateContribution(bonus, effectiveStats, loaded))
                    }
                } else null
            }
            val elementalMasteryBonus = reaction.elementalMasteryCoefficient * elementalMastery /
                (elementalMastery + reaction.elementalMasteryDenominator)
            val reactionDamageBonus = bonusEvaluations.sumOf { (_, _, evaluation) -> evaluation.value }
            val multiplier = reaction.baseMultiplier * (1.0 + elementalMasteryBonus + reactionDamageBonus)
            val levelMultiplier = reactionCatalog.characterLevelMultipliers[characterLevel - 1]
            val reactionBase = levelMultiplier * multiplier

            val resistanceInput = profile.damageTypes.firstOrNull { type ->
                type.element.equals(outputElement, ignoreCase = true) && type.resistanceInput in inputs
            }?.resistanceInput ?: reactionResistanceInput(outputElement).takeIf(inputs::containsKey)
                ?: damageType.resistanceInput
            val resistanceEvaluations = matchingReactionModifiers.mapNotNull { (effect, modifier) ->
                modifier.resistanceMitigation?.reduction?.let { reduction ->
                    Triple(effect, modifier, evaluateContribution(reduction, effectiveStats, loaded))
                }
            }
            val reactionResistanceReduction = resistanceEvaluations.sumOf { (_, _, evaluation) -> evaluation.value }
            val enemyResistance = inputs.getValue(resistanceInput) / 100.0
            val reactionResistanceMultiplier = resistanceMultiplier(enemyResistance - reactionResistanceReduction)

            var resultNonCritical = 0.0
            var resultCritical = 0.0
            var resultAverage = 0.0
            var resultReactionDamage: Double? = null
            var combinedAverage: Double? = null
            var utilityLabel: String? = null
            var utilityValue: Double? = null
            var resultLevelMultiplier: Double? = null
            var resultReactionBase: Double? = null
            var resultResistanceInput: String? = null
            var resultEnemyResistance: Double? = null
            var resultResistanceReduction = 0.0
            var resultResistanceMultiplier = 1.0

            when (reaction.kind) {
                "amplifying" -> {
                    resultNonCritical = nonCritical * multiplier
                    resultCritical = critical * multiplier
                    resultAverage = average * multiplier
                    combinedAverage = resultAverage
                }
                "additive" -> {
                    val additiveNonCritical = reactionBase * damageBonusMultiplier * defenseMultiplier *
                        resistanceMultiplier * elevationMultiplier
                    val additiveCritical = additiveNonCritical * (1.0 + critDamage)
                    val additiveAverage = additiveNonCritical * (1.0 + critRate * critDamage)
                    resultNonCritical = nonCritical + additiveNonCritical
                    resultCritical = critical + additiveCritical
                    resultAverage = average + additiveAverage
                    resultReactionDamage = additiveAverage
                    combinedAverage = resultAverage
                    resultLevelMultiplier = levelMultiplier
                    resultReactionBase = reactionBase
                    resultResistanceInput = damageType.resistanceInput
                    resultEnemyResistance = inputs.getValue(damageType.resistanceInput) / 100.0
                    resultResistanceMultiplier = resistanceMultiplier
                }
                "transformative" -> {
                    val transformativeDamage = reactionBase * reactionResistanceMultiplier
                    resultNonCritical = transformativeDamage
                    resultCritical = transformativeDamage
                    resultAverage = transformativeDamage
                    resultReactionDamage = transformativeDamage
                    combinedAverage = average + transformativeDamage
                    resultLevelMultiplier = levelMultiplier
                    resultReactionBase = reactionBase
                    resultResistanceInput = resistanceInput
                    resultEnemyResistance = enemyResistance
                    resultResistanceReduction = reactionResistanceReduction
                    resultResistanceMultiplier = reactionResistanceMultiplier
                }
                "shield" -> {
                    val crystallizeBase = reactionCatalog.crystallizeLevelMultipliers[characterLevel - 1] *
                        reaction.baseMultiplier
                    utilityLabel = "Base shield absorption"
                    utilityValue = crystallizeBase * (1.0 + elementalMasteryBonus + reactionDamageBonus)
                    resultLevelMultiplier = reactionCatalog.crystallizeLevelMultipliers[characterLevel - 1]
                    resultReactionBase = utilityValue
                }
                "utility" -> utilityLabel = "No direct damage"
            }

            DamageReactionResultV2(
                key = reaction.key,
                id = reaction.id,
                name = reaction.name,
                kind = reaction.kind,
                triggerElement = damageType.element,
                auraLabel = reaction.auraLabel,
                auraProviders = selectedMembers.filter { member ->
                    reaction.requiredTeamElements.any { it.equals(member.element, ignoreCase = true) }
                }.map { it.name },
                damageElement = reaction.damageElement,
                description = reaction.description,
                baseMultiplier = reaction.baseMultiplier,
                elementalMasteryCoefficient = reaction.elementalMasteryCoefficient,
                elementalMasteryDenominator = reaction.elementalMasteryDenominator,
                elementalMastery = elementalMastery,
                elementalMasteryBonus = elementalMasteryBonus,
                reactionDamageBonus = reactionDamageBonus,
                multiplier = multiplier,
                nonCritical = resultNonCritical,
                critical = resultCritical,
                average = resultAverage,
                levelMultiplier = resultLevelMultiplier,
                reactionBase = resultReactionBase,
                reactionDamage = resultReactionDamage,
                combinedAverage = combinedAverage,
                resistanceInput = resultResistanceInput,
                enemyResistance = resultEnemyResistance,
                resistanceReduction = resultResistanceReduction,
                resistanceMultiplier = resultResistanceMultiplier,
                utilityLabel = utilityLabel,
                utilityValue = utilityValue,
                bonusSources = bonusEvaluations.map { (effect, _, evaluation) ->
                    DamageReactionBonusSourceV2(effect.id, evaluation.value)
                },
                resistanceSources = resistanceEvaluations.map { (effect, _, evaluation) ->
                    DamageReactionBonusSourceV2(effect.id, evaluation.value)
                },
            )
        }
    }

    private fun reactionResistanceInput(element: String): String =
        "enemyReaction${element.lowercase().replaceFirstChar(Char::uppercase)}Resistance"

    private fun loadedProfile(characterKey: String): LoadedDamageProfileV2? {
        val key = characterKey.trim().lowercase()
        if (!supports(key)) return null
        return profiles.computeIfAbsent(key, ::loadProfile)
    }

    private fun loadProfile(key: String): LoadedDamageProfileV2 {
        val source = ClassPathResource(profileResource(key)).inputStream.use { input ->
            objectMapper.readValue(input, DamageFormulaProfileV2::class.java)
        }
        require(source.schemaVersion == 2) { "Damage formula profile '$key' must use schema version 2" }
        require(source.key == key) { "Damage formula profile '${source.key}' does not match '$key'" }
        val characterRoot = ClassPathResource(source.characterDataResource).inputStream.use(
            objectMapper::readTree,
        )
        val loaded = LoadedDamageProfileV2(source, characterRoot)
        validate(loaded)
        return loaded
    }

    private fun validate(loaded: LoadedDamageProfileV2) {
        val profile = loaded.source
        val inputIds = profile.inputs.map(DamageFormulaInputV2::id).toSet()
        require(inputIds.size == profile.inputs.size) { "Profile '${profile.key}' has duplicate input IDs" }
        require(REQUIRED_INPUTS.all(inputIds::contains)) {
            "Profile '${profile.key}' is missing core inputs: ${REQUIRED_INPUTS - inputIds}"
        }
        require(profile.inputs.all { it.min <= it.defaultValue && it.defaultValue <= it.max }) {
            "Profile '${profile.key}' has an input default outside its range"
        }
        val damageTypeIds = profile.damageTypes.map(DamageTypeDefinitionV2::id).toSet()
        require(damageTypeIds.size == profile.damageTypes.size) {
            "Profile '${profile.key}' has duplicate damage type IDs"
        }
        require(profile.damageTypes.all { it.defenseBehavior in setOf("standard", "bypass") }) {
            "Profile '${profile.key}' has an unsupported defense behavior"
        }
        profile.damageTypes.forEach { type ->
            require(type.resistanceInput in inputIds) { "Undefined resistance input '${type.resistanceInput}'" }
            type.innateDamageBonus?.let { validateContribution(it, inputIds, loaded) }
        }

        val formulas = profile.talents.flatMap(DamageTalentV2::formulas)
        val formulaIds = formulas.map(DamageFormulaV2::id).toSet()
        require(formulaIds.size == formulas.size) { "Profile '${profile.key}' has duplicate formula IDs" }
        formulas.forEach { formula ->
            require(formula.damageType in damageTypeIds) {
                "Formula '${formula.id}' references undefined damage type '${formula.damageType}'"
            }
            validateExpression(formula.base, inputIds, loaded)
            formula.damageBonus?.let { validateContribution(it, inputIds, loaded) }
            formula.defenseMitigation?.let { validateDefense(it, inputIds, loaded) }
            formula.resistanceMitigation?.let { validateResistance(it, inputIds, loaded) }
            formula.crit?.let { validateCrit(it, inputIds, loaded) }
        }

        val effectIds = profile.effects.map(DamageEffectV2::id).toSet()
        require(effectIds.size == profile.effects.size) { "Profile '${profile.key}' has duplicate effect IDs" }
        formulas.forEach { formula ->
            require(formula.requiredEffects.all(effectIds::contains)) {
                "Formula '${formula.id}' requires an undefined effect"
            }
            require(formula.excludedEffects.all(effectIds::contains)) {
                "Formula '${formula.id}' excludes an undefined effect"
            }
            require(formula.requiredEffects.none(formula.excludedEffects::contains)) {
                "Formula '${formula.id}' both requires and excludes the same effect"
            }
        }
        profile.effects.forEach { effect ->
            effect.stackingPriority?.let { validateExpression(it, inputIds, loaded) }
            require(effect.category.isNotBlank()) { "Effect '${effect.id}' has no category" }
            require(effect.ownershipRequirements.keys.all(inputIds::contains)) {
                "Effect '${effect.id}' has an undefined ownership input"
            }
            require(effect.id !in effect.requiresEffects) {
                "Effect '${effect.id}' depends on itself"
            }
            require(effect.requiresEffects.all(effectIds::contains)) {
                "Effect '${effect.id}' depends on an undefined effect"
            }
            effect.activation.conditions.forEach { condition ->
                validateActivationCondition(condition, inputIds, effect.id)
            }
            effect.statChanges.forEach { change ->
                require(change.requiresEffects.all(effectIds::contains)) { "Undefined stat-change dependency" }
                require(change.stat in inputIds) {
                    "Effect '${effect.id}' changes undefined stat '${change.stat}'"
                }
                require(change.operation in setOf("flatAdd", "percentAdd")) {
                    "Effect '${effect.id}' has unsupported stat operation '${change.operation}'"
                }
                require(change.minimum == null || change.maximum == null || change.minimum <= change.maximum) {
                    "Effect '${effect.id}' has invalid stat-change bounds"
                }
                validateExpression(change.value, inputIds, loaded)
                change.conditions.forEach { condition ->
                    validateActivationCondition(condition, inputIds, effect.id)
                }
            }
            effect.formulaModifiers.forEach { modifier ->
                require(modifier.targets.formulaIds.all(formulaIds::contains)) {
                    "Effect '${effect.id}' targets undefined formulas"
                }
                // Support effects may target damage forms not used by the current carry.
                require(modifier.requiresEffects.all(effectIds::contains)) { "Undefined modifier dependency" }
                modifier.conditions.forEach { validateActivationCondition(it, inputIds, effect.id) }
                modifier.base?.let { validateExpression(it, inputIds, loaded) }
                modifier.baseDamageBonus?.let { validateContribution(it, inputIds, loaded) }
                modifier.damageBonus?.let { validateContribution(it, inputIds, loaded) }
                modifier.defenseMitigation?.let { validateDefense(it, inputIds, loaded) }
                modifier.resistanceMitigation?.let { validateResistance(it, inputIds, loaded) }
                modifier.crit?.let { validateCrit(it, inputIds, loaded) }
                modifier.elevation?.let { validateContribution(it, inputIds, loaded) }
                modifier.postBonusBase?.let { validateExpression(it, inputIds, loaded) }
            }
            effect.metrics.forEach { metric ->
                metric.conditions.forEach { validateActivationCondition(it, inputIds, effect.id) }
                require(metric.unit in setOf("number", "percentPoints", "ratio")) {
                    "Effect '${effect.id}' has unsupported metric unit '${metric.unit}'"
                }
                validateExpression(metric.expression, inputIds, loaded)
            }
        }
    }

    private fun validateActivationCondition(
        condition: EffectActivationConditionV2,
        inputIds: Set<String>,
        effectId: String,
    ) {
        when (condition.type) {
            "inputAtLeast", "inputAtMost" -> {
                require(condition.input in inputIds) {
                    "Effect '$effectId' references undefined activation input '${condition.input}'"
                }
                requireNotNull(condition.value) {
                    "Effect '$effectId' activation condition is missing its value"
                }
            }
            "teamMember" -> require(!condition.teamMember.isNullOrBlank()) {
                "Effect '$effectId' team condition is missing its member"
            }
            "contextAtLeast" -> require(condition.input != null && condition.value != null) {
                "Effect '$effectId' context condition is incomplete"
            }
            else -> error("Effect '$effectId' has unsupported activation condition '${condition.type}'")
        }
    }

    private fun validateDefense(
        part: DefenseMitigationContributionV2,
        inputIds: Set<String>,
        loaded: LoadedDamageProfileV2,
    ) {
        part.reduction?.let { validateContribution(it, inputIds, loaded) }
        part.ignore?.let { validateContribution(it, inputIds, loaded) }
    }

    private fun validateResistance(
        part: ResistanceMitigationContributionV2,
        inputIds: Set<String>,
        loaded: LoadedDamageProfileV2,
    ) {
        part.reduction?.let { validateContribution(it, inputIds, loaded) }
    }

    private fun validateCrit(
        part: CritContributionV2,
        inputIds: Set<String>,
        loaded: LoadedDamageProfileV2,
    ) {
        part.additionalRate?.let { validateContribution(it, inputIds, loaded) }
        part.additionalDamage?.let { validateContribution(it, inputIds, loaded) }
    }

    private fun validateContribution(
        contribution: FormulaContributionV2,
        inputIds: Set<String>,
        loaded: LoadedDamageProfileV2,
    ) {
        require(contribution.unit in setOf("ratio", "percentPoints")) {
            "Unsupported contribution unit '${contribution.unit}'"
        }
        validateExpression(contribution.expression, inputIds, loaded)
    }

    private fun validateExpression(
        expression: FormulaExpressionV2,
        inputIds: Set<String>,
        loaded: LoadedDamageProfileV2,
    ) {
        when (expression.type) {
            "constant" -> requireNotNull(expression.value) { "Constant expression is missing value" }
            "input" -> require(expression.input in inputIds) {
                "Expression references undefined input '${expression.input}'"
            }
            "talentParameter" -> {
                val talent = requireNotNull(expression.talent) { "Talent expression is missing talent" }
                val parameter = requireNotNull(expression.parameter) { "Talent expression is missing parameter" }
                require(expression.levelInput in inputIds) {
                    "Talent expression references undefined level input '${expression.levelInput}'"
                }
                loaded.parameterValues(talent, parameter, expression.characterKey)
            }
            "teamMax" -> require(expression.input in loaded.source.teamStatInputs) { "Undefined team stat" }
            "add", "multiply", "min", "max" -> require(expression.operands.isNotEmpty()) {
                "Expression '${expression.type}' requires operands"
            }
            "divide" -> require(expression.operands.size == 2) {
                "Divide expression requires exactly two operands"
            }
            else -> error("Unsupported formula expression '${expression.type}'")
        }
        expression.operands.forEach { operand -> validateExpression(operand, inputIds, loaded) }
    }

    private fun evaluateContribution(
        contribution: FormulaContributionV2,
        inputs: Map<String, Double>,
        loaded: LoadedDamageProfileV2,
    ): ExpressionEvaluationV2 {
        val evaluation = evaluate(contribution.expression, inputs, loaded)
        return if (contribution.unit == "percentPoints") {
            evaluation.copy(value = evaluation.value / 100.0)
        } else {
            evaluation
        }
    }

    private fun evaluate(
        expression: FormulaExpressionV2,
        inputs: Map<String, Double>,
        loaded: LoadedDamageProfileV2,
    ): ExpressionEvaluationV2 = when (expression.type) {
        "constant" -> ExpressionEvaluationV2(requireNotNull(expression.value))
        "input" -> {
            val input = requireNotNull(expression.input)
            val value = inputs.getValue(input)
            ExpressionEvaluationV2(
                value,
                listOf(ResolvedFormulaReferenceV2("input", input, value)),
            )
        }
        "talentParameter" -> {
            val talent = requireNotNull(expression.talent)
            val parameter = requireNotNull(expression.parameter)
            val levelInput = requireNotNull(expression.levelInput)
            val values = loaded.parameterValues(talent, parameter, expression.characterKey)
            val level = inputs.getValue(levelInput).roundToInt().coerceIn(1, values.size)
            val value = values[level - 1]
            ExpressionEvaluationV2(
                value,
                listOf(
                    ResolvedFormulaReferenceV2(
                        type = "talentParameter",
                        key = "$talent.$parameter",
                        value = value,
                        talentLevel = level,
                        characterKey = expression.characterKey,
                    ),
                ),
            )
        }
        "teamMax" -> {
            val references = loaded.source.teamStatInputs.getValue(requireNotNull(expression.input))
                .map { ResolvedFormulaReferenceV2("input", it, inputs.getValue(it)) }
            ExpressionEvaluationV2(references.maxOfOrNull { it.value } ?: 0.0, references)
        }
        else -> {
            val operands = expression.operands.map { operand -> evaluate(operand, inputs, loaded) }
            val value = when (expression.type) {
                "add" -> operands.sumOf(ExpressionEvaluationV2::value)
                "multiply" -> operands.fold(1.0) { total, operand -> total * operand.value }
                "divide" -> {
                    require(operands[1].value != 0.0) { "Formula expression divides by zero" }
                    operands[0].value / operands[1].value
                }
                "min" -> operands.minOf(ExpressionEvaluationV2::value)
                "max" -> operands.maxOf(ExpressionEvaluationV2::value)
                else -> error("Unsupported formula expression '${expression.type}'")
            }
            ExpressionEvaluationV2(value, operands.flatMap(ExpressionEvaluationV2::references))
        }
    }

    private fun FormulaTargetsV2.matches(formula: DamageFormulaV2, damageType: DamageTypeDefinitionV2): Boolean =
        (formulaIds.isEmpty() || formula.id in formulaIds) &&
            requiredTags.all(formula.tags::contains) &&
            (anyTags.isEmpty() || anyTags.any(formula.tags::contains)) &&
            (damageKinds.isEmpty() || damageType.kind in damageKinds) &&
            (elements.isEmpty() || elements.any { it.equals(damageType.element, ignoreCase = true) })

    private fun activationConditionIsSatisfied(
        condition: EffectActivationConditionV2,
        inputs: Map<String, Double>,
        profile: DamageFormulaProfileV2,
    ): Boolean = when (condition.type) {
        "inputAtLeast" -> inputs.getValue(requireNotNull(condition.input)) >= requireNotNull(condition.value)
        "inputAtMost" -> inputs.getValue(requireNotNull(condition.input)) <= requireNotNull(condition.value)
        "teamMember" -> profile.selectedTeam.any { it.equals(condition.teamMember, ignoreCase = true) }
        "contextAtLeast" -> profile.teamContext.getOrDefault(condition.input, 0.0) >= requireNotNull(condition.value)
        else -> error("Unsupported activation condition '${condition.type}'")
    }

    private fun resistanceMultiplier(resistance: Double): Double = when {
        resistance < 0.0 -> 1.0 - resistance / 2.0
        resistance < 0.75 -> 1.0 - resistance
        else -> 1.0 / (4.0 * resistance + 1.0)
    }

    private fun profileResource(key: String): String = "data/damage-formulas-v2/$key.json"

    private data class LoadedDamageProfileV2(
        val source: DamageFormulaProfileV2,
        val characterRoot: JsonNode,
        val supportRoots: Map<String, JsonNode> = emptyMap(),
    ) {
        fun parameterValues(talent: String, parameter: String, characterKey: String? = null): List<Double> {
            val support = source.supportProfiles.find { it.key == characterKey }
            support?.talentParameters?.get("$talent.$parameter")?.let { values ->
                require(values.isNotEmpty() && values.all(Double::isFinite)) { "Invalid support talent table" }
                return values
            }
            val root = if (characterKey == null || characterKey == source.characterKey) characterRoot
                else requireNotNull(supportRoots[characterKey]) { "Missing talent data for '$characterKey'" }
            val values = root.path("talents")
                .path(talent)
                .path("attributes")
                .path("parameters")
                .path(parameter)
            require(values.isArray && values.size() > 0) {
                "Talent parameter '$talent.$parameter' is missing for '${source.key}'"
            }
            return values.map(JsonNode::asDouble)
        }
    }

    private data class ExpressionEvaluationV2(
        val value: Double,
        val references: List<ResolvedFormulaReferenceV2> = emptyList(),
    )

    private companion object {
        const val REACTION_RESOURCE = "data/damage-formulas-v2/elemental-reactions.json"
        val REQUIRED_INPUTS = setOf(
            "characterLevel",
            "enemyLevel",
            "attack",
            "elementalMastery",
            "elementalDamageBonus",
            "critRate",
            "critDamage",
            "enemyResistance",
            "defenseReduction",
            "defenseIgnore",
        )
    }
}
