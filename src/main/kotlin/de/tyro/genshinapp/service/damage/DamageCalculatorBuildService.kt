package de.tyro.genshinapp.service.damage

import de.tyro.genshinapp.service.artifact.ArtifactOptimizationService
import de.tyro.genshinapp.service.artifact.OptimizerCombatStatService
import de.tyro.genshinapp.service.planning.CharacterTargetService
import de.tyro.genshinapp.service.planning.PlayerPlanningService
import de.tyro.genshinapp.service.player.PlayerEquipmentService
import de.tyro.genshinapp.service.player.PlayerSnapshotStore
import org.springframework.stereotype.Service

@Service
class DamageCalculatorBuildService(
    private val snapshotStore: PlayerSnapshotStore,
    private val planningService: PlayerPlanningService,
    private val equipmentService: PlayerEquipmentService,
    private val artifactOptimizationService: ArtifactOptimizationService,
    private val optimizerCombatStatService: OptimizerCombatStatService,
    private val targetService: CharacterTargetService,
) {
    fun applyCurrentValues(
        profile: DamageCalculatorProfile,
        userId: Long,
    ): DamageCalculatorProfile {
        val snapshot = snapshotStore.current(userId) ?: return profile
        val valuesByCharacter = mutableMapOf<String, CurrentCharacterValues?>()
        fun values(characterKey: String): CurrentCharacterValues? =
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
                )
                CurrentCharacterValues(
                    level = state.level.toDouble(),
                    constellation = state.constellation.toDouble(),
                    normalTalent = state.normalTalent.toDouble(),
                    skillTalent = state.skillTalent.toDouble(),
                    burstTalent = state.burstTalent.toDouble(),
                    totalAttack = baseStats.totalAttack(buildStats.totalValues),
                    stats = buildStats.totalValues,
                )
            }

        return profile.copy(
            inputSections = profile.inputSections.map { section ->
                section.copy(
                    inputs = section.inputs.map inputMap@{ input ->
                        val source = input.initialValueSource ?: return@inputMap input
                        val characterKey = source.characterKey ?: profile.characterKey
                        val current = values(characterKey) ?: return@inputMap input
                        val value = current.value(source.valueKey) ?: return@inputMap input
                        input.copy(
                            defaultValue = value.coerceIn(input.min, input.max),
                            currentValueLoaded = true,
                        )
                    },
                )
            },
        )
    }

    private data class CurrentCharacterValues(
        val level: Double,
        val constellation: Double,
        val normalTalent: Double,
        val skillTalent: Double,
        val burstTalent: Double,
        val totalAttack: Double?,
        val stats: Map<String, Double>,
    ) {
        fun value(key: String): Double? = when (key) {
            "level" -> level
            "constellation" -> constellation
            "normalTalent" -> normalTalent
            "skillTalent" -> skillTalent
            "burstTalent" -> burstTalent
            "totalAttack" -> totalAttack
            else -> key.removePrefix("stat:")
                .takeIf { key.startsWith("stat:") }
                ?.let { stats.getOrDefault(it, 0.0) }
        }
    }
}
