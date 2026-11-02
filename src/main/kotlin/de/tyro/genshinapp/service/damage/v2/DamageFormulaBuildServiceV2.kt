package de.tyro.genshinapp.service.damage.v2

import de.tyro.genshinapp.service.artifact.ArtifactOptimizationService
import de.tyro.genshinapp.service.artifact.OptimizerCombatStatService
import de.tyro.genshinapp.service.catalog.CharacterStatCatalog
import de.tyro.genshinapp.service.planning.CharacterTargetService
import de.tyro.genshinapp.service.planning.PlayerPlanningService
import de.tyro.genshinapp.service.player.PlayerEquipmentService
import de.tyro.genshinapp.service.player.PlayerSnapshotStore
import de.tyro.genshinapp.model.PlayerSnapshot
import org.springframework.stereotype.Service

@Service
class DamageFormulaBuildServiceV2(
    private val snapshotStore: PlayerSnapshotStore,
    private val planningService: PlayerPlanningService,
    private val equipmentService: PlayerEquipmentService,
    private val artifactOptimizationService: ArtifactOptimizationService,
    private val optimizerCombatStatService: OptimizerCombatStatService,
    private val targetService: CharacterTargetService,
    private val artifactService: DamageFormulaArtifactServiceV2,
    private val characterStatCatalog: CharacterStatCatalog,
    private val weaponService: DamageFormulaWeaponServiceV2,
) {
    fun comparisonBaseValues(snapshot: PlayerSnapshot, characterKey: String): Map<String, Double> {
        val state = planningService.findCharacterState(snapshot, characterKey) ?: return emptyMap()
        val stats = characterStatCatalog.resolve(state.key, state.level, state.ascension) ?: return emptyMap()
        return mapOf("baseHp" to stats.getValue("hp"), "baseDefense" to stats.getValue("def"))
    }

    fun applyCurrentValues(
        profile: DamageFormulaProfileV2,
        userId: Long,
    ): DamageFormulaProfileV2 = applySnapshot(profile, userId, snapshotStore.current(userId))

    /** Explicit snapshot entry point for read-only hypothetical builds. */
    fun applySnapshot(
        profile: DamageFormulaProfileV2,
        userId: Long,
        snapshot: PlayerSnapshot?,
    ): DamageFormulaProfileV2 {
        val equippedProfile = weaponService.attach(artifactService.attach(profile, snapshot?.artifacts.orEmpty()), snapshot?.weapons.orEmpty())
        if (snapshot == null) return equippedProfile
        val valuesByCharacter = mutableMapOf<String, CurrentValuesV2?>()
        fun values(characterKey: String): CurrentValuesV2? =
            valuesByCharacter.getOrPut(characterKey) {
                val state = planningService.findCharacterState(snapshot, characterKey)
                    ?: return@getOrPut null
                val equipment = equipmentService.equipmentFor(snapshot, state)
                val additionalStats = targetService.find(userId, characterKey)?.additionalStats.orEmpty()
                val baseStats = optimizerCombatStatService.resolve(
                    character = state,
                    weapon = equipment.weapon,
                    additionalStats = additionalStats,
                )
                val buildStats = artifactOptimizationService.summarizeCurrentBuild(
                    artifacts = equipment.artifacts,
                    baseStats = baseStats,
                    includeSetBonuses = false,
                )
                val characterStats = characterStatCatalog.resolve(state.key, state.level, state.ascension)
                fun totalBaseStat(key: String): Double? = characterStats?.get(key)?.let { base ->
                    base * (1.0 + buildStats.totalValues.getOrDefault("${key}_", 0.0) / 100.0) +
                        buildStats.totalValues.getOrDefault(key, 0.0)
                }
                CurrentValuesV2(
                    level = state.level.toDouble(),
                    constellation = state.constellation.toDouble(),
                    normalTalent = state.normalTalent.toDouble(),
                    skillTalent = state.skillTalent.toDouble(),
                    burstTalent = state.burstTalent.toDouble(),
                    totalAttack = baseStats.totalAttack(buildStats.totalValues),
                    baseAttack = baseStats.combinedBaseAttack,
                    totalHp = totalBaseStat("hp"),
                    baseHp = characterStats?.get("hp"),
                    totalDefense = totalBaseStat("def"),
                    baseDefense = characterStats?.get("def"),
                    stats = buildStats.totalValues,
                )
            }

        val inputs = equippedProfile.inputs.map inputMap@{ input ->
            val source = input.initialValueSource ?: return@inputMap input
            val characterKey = source.characterKey ?: profile.characterKey
            val current = values(characterKey) ?: return@inputMap input
            val value = current.value(source.valueKey) ?: return@inputMap input
            input.copy(
                defaultValue = value.coerceIn(input.min, input.max),
                currentValueLoaded = true,
            )
        }
        val defaultValues = inputs.associate { input -> input.id to input.defaultValue }
        return equippedProfile.copy(
            inputs = inputs,
            effects = equippedProfile.effects.map { effect ->
                if (effect.ownershipRequirements.isEmpty()) {
                    effect
                } else {
                    effect.copy(
                        defaultEnabled = effect.ownershipRequirements.all { (input, minimum) ->
                            defaultValues.getValue(input) >= minimum
                        },
                    )
                }
            },
        )
    }

    private data class CurrentValuesV2(
        val level: Double,
        val constellation: Double,
        val normalTalent: Double,
        val skillTalent: Double,
        val burstTalent: Double,
        val totalAttack: Double?,
        val baseAttack: Double?,
        val totalHp: Double?,
        val baseHp: Double?,
        val totalDefense: Double?,
        val baseDefense: Double?,
        val stats: Map<String, Double>,
    ) {
        fun value(key: String): Double? = when (key) {
            "level" -> level
            "constellation" -> constellation
            "normalTalent" -> normalTalent
            "skillTalent" -> skillTalent
            "burstTalent" -> burstTalent
            "totalAttack" -> totalAttack
            "baseAttack" -> baseAttack
            "totalHp" -> totalHp
            "baseHp" -> baseHp
            "totalDefense" -> totalDefense
            "baseDefense" -> baseDefense
            else -> key.removePrefix("stat:")
                .takeIf { key.startsWith("stat:") }
                ?.let { stats.getOrDefault(it, 0.0) }
        }
    }
}
