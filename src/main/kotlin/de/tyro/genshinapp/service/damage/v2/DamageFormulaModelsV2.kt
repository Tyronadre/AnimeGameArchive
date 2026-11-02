package de.tyro.genshinapp.service.damage.v2

data class DamageFormulaProfileV2(
    val schemaVersion: Int,
    val key: String,
    val characterKey: String,
    val name: String,
    val element: String,
    val characterDataResource: String = "",
    val inputs: List<DamageFormulaInputV2> = emptyList(),
    val damageTypes: List<DamageTypeDefinitionV2> = emptyList(),
    val talents: List<DamageTalentV2> = emptyList(),
    val effects: List<DamageEffectV2> = emptyList(),
    val moonsignContribution: Int = 0,
    val statBindings: Map<String, String> = emptyMap(),
    val defaultTeam: List<String> = emptyList(),
    val teamMembers: List<DamageTeamMemberV2> = emptyList(),
    val selectedTeam: Set<String> = emptySet(),
    val teamContext: Map<String, Double> = emptyMap(),
    val teamStatInputs: Map<String, List<String>> = emptyMap(),
    val supportProfiles: List<DamageSupportProfileV2> = emptyList(),
    val artifactLoadouts: List<DamageArtifactLoadoutV2> = emptyList(),
    val weaponType: String = "",
    /** Editable combat-stat input IDs, grouped by selected character and logical stat. */
    val characterStatInputs: Map<String, Map<String, String>> = emptyMap(),
    /** Character and talent level input IDs, grouped by selected character. */
    val characterProgressInputs: Map<String, Map<String, String>> = emptyMap(),
    /** Additional authored teammate controls that feed that character's buffs. */
    val characterBuffInputs: Map<String, List<String>> = emptyMap(),
    val talentParameters: Map<String, List<Double>> = emptyMap(),
    val notes: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    /** Optional preview values for inherited standard inputs, keyed by logical input name. */
    val inputDefaults: Map<String, Double> = emptyMap(),
)

/** Derived selected-member metadata for the V2 response; never a separately authored file. */
data class DamageSupportProfileV2(
    val schemaVersion: Int,
    val key: String,
    val name: String,
    val element: String,
    val moonsignContribution: Int = 0,
    val characterDataResource: String? = null,
    val talentParameters: Map<String, List<Double>> = emptyMap(),
    val statBindings: Map<String, String> = emptyMap(),
    val inputs: List<DamageFormulaInputV2> = emptyList(),
    val effects: List<DamageEffectV2> = emptyList(),
    val notes: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val weaponType: String = "",
)

data class DamageTeamMemberV2(
    val key: String,
    val name: String,
    val element: String,
    val moonsignContribution: Int,
    val iconUrl: String? = null,
    val weaponType: String = "",
    val calculatorAvailable: Boolean = false,
    val region: String = "",
)

data class DamageTeamRulesV2(
    val hexereiCharacters: List<String> = emptyList(),
    val inputs: List<DamageFormulaInputV2> = emptyList(),
    val effects: List<DamageEffectV2> = emptyList(),
)

data class DamageFormulaInputV2(
    val id: String,
    val label: String,
    val defaultValue: Double,
    val min: Double,
    val max: Double,
    val step: Double,
    val unit: String = "number",
    val initialValueSource: DamageFormulaInitialValueSourceV2? = null,
    val currentValueLoaded: Boolean = false,
    val section: String? = null,
    val hintTag: String? = null,
    val description: String? = null,
    val defaultContext: String? = null,
)

data class DamageFormulaInitialValueSourceV2(
    val valueKey: String,
    val characterKey: String? = null,
)

data class DamageTypeDefinitionV2(
    val id: String,
    val element: String,
    val defenseBehavior: String = "standard",
    val appliesElementalDamageBonus: Boolean = true,
    val allowsCriticalHits: Boolean = true,
    val innateDamageBonus: FormulaContributionV2? = null,
    val appliesOtherDamageBonus: Boolean = true,
    val resistanceInput: String = "enemyResistance",
    val independentProc: Boolean = false,
    val kind: String = "talent",
    /** Bound at composition time for a hit using a different element from its owner. */
    val elementalBonusInput: String? = null,
)

data class DamageTalentV2(
    val key: String,
    val name: String,
    val formulas: List<DamageFormulaV2>,
)

/** Reusable directional reaction data. New reaction families can extend this catalog without changing character files. */
data class DamageReactionCatalogV2(
    val schemaVersion: Int,
    /** Flat reaction scaling for character levels 1 through 90. */
    val characterLevelMultipliers: List<Double>,
    /** Base Crystallize shield absorption for character levels 1 through 90. */
    val crystallizeLevelMultipliers: List<Double>,
    val reactions: List<DamageReactionDefinitionV2>,
    val sources: List<String> = emptyList(),
)

data class DamageReactionDefinitionV2(
    val key: String,
    val id: String,
    val name: String,
    val kind: String,
    val triggerElements: List<String>,
    val requiredTeamElements: List<String>,
    val auraLabel: String,
    val damageElement: String? = null,
    val description: String? = null,
    val requiredFormulaTagsAny: List<String> = emptyList(),
    val baseMultiplier: Double,
    val elementalMasteryCoefficient: Double,
    val elementalMasteryDenominator: Double,
)

data class DamageFormulaV2(
    val id: String,
    val label: String,
    val damageType: String,
    val tags: List<String> = emptyList(),
    val requiredEffects: List<String> = emptyList(),
    val excludedEffects: List<String> = emptyList(),
    val base: FormulaExpressionV2,
    val damageBonus: FormulaContributionV2? = null,
    val defenseMitigation: DefenseMitigationContributionV2? = null,
    val resistanceMitigation: ResistanceMitigationContributionV2? = null,
    val crit: CritContributionV2? = null,
    /** physicalOnly (default), overrideable, or locked against external weapon infusion. */
    val infusionRule: String = "physicalOnly",
)

/**
 * Small, serializable expression tree used instead of hard-coded character lambdas.
 * Supported nodes are constant, input, talentParameter, add, multiply, divide, min, and max.
 */
data class FormulaExpressionV2(
    val type: String,
    val value: Double? = null,
    val input: String? = null,
    val talent: String? = null,
    val parameter: String? = null,
    val levelInput: String? = null,
    val characterKey: String? = null,
    val operands: List<FormulaExpressionV2> = emptyList(),
)

data class FormulaContributionV2(
    val expression: FormulaExpressionV2,
    val unit: String = "percentPoints",
)

data class DefenseMitigationContributionV2(
    val reduction: FormulaContributionV2? = null,
    val ignore: FormulaContributionV2? = null,
    val bypass: Boolean? = null,
)

data class ResistanceMitigationContributionV2(
    val reduction: FormulaContributionV2? = null,
)

data class CritContributionV2(
    val additionalRate: FormulaContributionV2? = null,
    val additionalDamage: FormulaContributionV2? = null,
)

data class DamageEffectV2(
    val id: String,
    val name: String,
    val source: String,
    val description: String,
    val category: String = "passive",
    val impact: String? = null,
    val defaultEnabled: Boolean = true,
    val ownershipRequirements: Map<String, Double> = emptyMap(),
    val requiresEffects: List<String> = emptyList(),
    val activation: EffectActivationV2,
    val statChanges: List<StatChangeV2> = emptyList(),
    val formulaModifiers: List<FormulaModifierV2> = emptyList(),
    val metrics: List<EffectMetricV2> = emptyList(),
    val stackingGroup: String? = null,
    val stackingPriority: FormulaExpressionV2? = null,
    val effectTags: List<String> = emptyList(),
    val artifactOrigin: DamageArtifactOriginV2? = null,
    val recipient: String = "self",
    val excludeSource: Boolean = false,
    val sourceCharacterKey: String? = null,
)

data class EffectActivationV2(
    val event: String,
    val talentKey: String? = null,
    val conditions: List<EffectActivationConditionV2> = emptyList(),
)

data class EffectActivationConditionV2(
    val type: String,
    val input: String? = null,
    val value: Double? = null,
    val teamMember: String? = null,
)

data class StatChangeV2(
    val stat: String,
    val operation: String,
    val value: FormulaExpressionV2,
    val conditions: List<EffectActivationConditionV2> = emptyList(),
    val minimum: Double? = null,
    val maximum: Double? = null,
    val phase: Int = 0,
    val requiresEffects: List<String> = emptyList(),
    val recipient: String? = null,
    val excludeSource: Boolean = false,
)

data class FormulaModifierV2(
    val targets: FormulaTargetsV2,
    val conditions: List<EffectActivationConditionV2> = emptyList(),
    val requiresEffects: List<String> = emptyList(),
    val base: FormulaExpressionV2? = null,
    val baseDamageBonus: FormulaContributionV2? = null,
    val damageBonus: FormulaContributionV2? = null,
    val defenseMitigation: DefenseMitigationContributionV2? = null,
    val resistanceMitigation: ResistanceMitigationContributionV2? = null,
    val crit: CritContributionV2? = null,
    val elevation: FormulaContributionV2? = null,
    /** Added after the DMG bonus multiplier, before mitigation, elevation, and CRIT. */
    val postBonusBase: FormulaExpressionV2? = null,
    /** Each matching contribution multiplies the completed base by (1 + contribution). */
    val baseDamageMultiplier: FormulaContributionV2? = null,
    val recipient: String? = null,
    val excludeSource: Boolean = false,
    val infusion: WeaponInfusionV2? = null,
)

data class WeaponInfusionV2(
    val damageType: String,
    val weaponTypes: List<String> = listOf("Sword", "Claymore", "Polearm"),
    /** Resolves a configured snapshot; weapon-gauge reactions and refresh timing are not simulated. */
    val priority: Int = 0,
)

data class EffectMetricV2(
    val label: String,
    val expression: FormulaExpressionV2,
    val unit: String = "number",
    val conditions: List<EffectActivationConditionV2> = emptyList(),
)

data class FormulaTargetsV2(
    val formulaIds: List<String> = emptyList(),
    val requiredTags: List<String> = emptyList(),
    val elements: List<String> = emptyList(),
    val anyTags: List<String> = emptyList(),
    val damageKinds: List<String> = emptyList(),
)

data class DamageCalculationRequestV2(
    val inputs: Map<String, Double> = emptyMap(),
    val enabledEffects: Map<String, Boolean> = emptyMap(),
    val teamKeys: Set<String> = emptySet(),
)

data class DamageFormulaDocumentV2(
    val profile: DamageFormulaProfileV2,
    val defaultCalculation: DamageCalculationV2,
    val teamBuilds: List<StoredTeamBuildV2> = emptyList(),
    val contextFingerprint: String? = null,
)

data class DamageCalculationV2(
    val inputs: Map<String, Double>,
    val effectiveStats: Map<String, Double>,
    val activeEffectIds: Set<String>,
    val results: List<DamageFormulaResultV2>,
    val effectMetrics: Map<String, List<EvaluatedFormulaPartV2>> = emptyMap(),
    val statChanges: List<ResolvedStatChangeV2> = emptyList(),
)

data class ResolvedStatChangeV2(val effectId: String, val stat: String, val amount: Double,
    val references: List<ResolvedFormulaReferenceV2>, val expression: FormulaExpressionV2)

data class DamageFormulaResultV2(
    val formulaId: String,
    val talentKey: String,
    val damageType: String,
    val element: String,
    val base: EvaluatedFormulaPartV2,
    val damageBonus: DamageBonusResultV2,
    val defenseMitigation: MitigationResultV2,
    val resistanceMitigation: MitigationResultV2,
    val crit: CritResultV2,
    val elevation: FormulaMultiplierResultV2,
    val nonCritical: Double,
    val critical: Double,
    val average: Double,
    val appliedEffectIds: Set<String>,
    val postBonusBase: EvaluatedFormulaPartV2 = EvaluatedFormulaPartV2(0.0, emptyList()),
    val reactions: List<DamageReactionResultV2> = emptyList(),
    val infusionSourceEffectId: String? = null,
)

data class DamageReactionResultV2(
    val key: String,
    val id: String,
    val name: String,
    val kind: String,
    val triggerElement: String,
    val auraLabel: String,
    val auraProviders: List<String>,
    val damageElement: String?,
    val description: String? = null,
    val baseMultiplier: Double,
    val elementalMasteryCoefficient: Double,
    val elementalMasteryDenominator: Double,
    val elementalMastery: Double,
    val elementalMasteryBonus: Double,
    val reactionDamageBonus: Double,
    val multiplier: Double,
    val nonCritical: Double,
    val critical: Double,
    val average: Double,
    /** Character-level scaling used by additive and transformative reactions. */
    val levelMultiplier: Double? = null,
    /** Flat reaction base after EM/reaction bonuses, before the remaining outgoing multipliers. */
    val reactionBase: Double? = null,
    /** Reaction-only contribution; for additive reactions this is its average contribution. */
    val reactionDamage: Double? = null,
    /** Direct hit plus the separate reaction damage where that is meaningful. */
    val combinedAverage: Double? = null,
    val resistanceInput: String? = null,
    val enemyResistance: Double? = null,
    val resistanceReduction: Double = 0.0,
    val resistanceMultiplier: Double = 1.0,
    val utilityLabel: String? = null,
    val utilityValue: Double? = null,
    val bonusSources: List<DamageReactionBonusSourceV2> = emptyList(),
    val resistanceSources: List<DamageReactionBonusSourceV2> = emptyList(),
)

data class DamageReactionBonusSourceV2(
    val effectId: String,
    val value: Double,
)

data class EvaluatedFormulaPartV2(
    val value: Double,
    val references: List<ResolvedFormulaReferenceV2>,
    val formulaValue: Double = value,
    val additiveValue: Double = 0.0,
    val multiplier: Double = 1.0,
    val independentMultiplier: Double = 1.0,
)

data class ResolvedFormulaReferenceV2(
    val type: String,
    val key: String,
    val value: Double,
    val talentLevel: Int? = null,
    val characterKey: String? = null,
)

data class DamageBonusResultV2(
    val elementalBonus: Double,
    val otherBonus: Double,
    val innateBonus: Double,
    val authoredBonus: Double,
    val multiplier: Double,
    val references: List<ResolvedFormulaReferenceV2>,
)

data class MitigationResultV2(
    val reduction: Double,
    val ignore: Double = 0.0,
    val bypassed: Boolean = false,
    val multiplier: Double,
    val references: List<ResolvedFormulaReferenceV2>,
)

data class CritResultV2(
    val additionalRate: Double,
    val additionalDamage: Double,
    val effectiveRate: Double,
    val effectiveDamage: Double,
    val references: List<ResolvedFormulaReferenceV2>,
)

data class FormulaMultiplierResultV2(
    val authoredBonus: Double,
    val multiplier: Double,
    val references: List<ResolvedFormulaReferenceV2>,
)
