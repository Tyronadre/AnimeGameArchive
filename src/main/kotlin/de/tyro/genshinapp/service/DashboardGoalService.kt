package de.tyro.genshinapp.service

import de.tyro.genshinapp.entity.DashboardGoal
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.repository.DashboardGoalRepository
import de.tyro.genshinapp.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DashboardGoalService(
    private val goalRepository: DashboardGoalRepository,
    private val userRepository: UserRepository,
) {
    @Transactional(readOnly = true)
    fun findAll(userId: Long): Set<DashboardGoalSelection> =
        goalRepository.findAllByUser_Id(userId)
            .mapNotNullTo(linkedSetOf()) { entity ->
                val type = DashboardGoalType.fromKey(entity.goalType) ?: return@mapNotNullTo null
                DashboardGoalSelection(entity.characterKey, type)
            }

    @Transactional
    fun replace(
        userId: Long,
        selections: Collection<DashboardGoalSelection>,
    ): Set<DashboardGoalSelection> {
        val normalized = selections.mapNotNullTo(linkedSetOf()) { selection ->
            val characterKey = GoodKeyNormalizer.normalize(selection.characterKey)
            characterKey.takeIf(String::isNotBlank)?.let {
                DashboardGoalSelection(it, selection.type)
            }
        }
        val existing = goalRepository.findAllByUser_Id(userId)
        val existingBySelection = existing.associateBy {
            DashboardGoalSelection(
                characterKey = it.characterKey,
                type = requireNotNull(DashboardGoalType.fromKey(it.goalType)),
            )
        }
        existing.filter { entity ->
            DashboardGoalSelection(
                entity.characterKey,
                requireNotNull(DashboardGoalType.fromKey(entity.goalType)),
            ) !in normalized
        }.forEach(goalRepository::delete)

        val user by lazy {
            userRepository.findById(userId)
                .orElseThrow { IllegalArgumentException("User not found") }
        }
        normalized.filter { it !in existingBySelection }.forEach { selection ->
            goalRepository.save(
                DashboardGoal().also {
                    it.user = user
                    it.characterKey = selection.characterKey
                    it.goalType = selection.type.key
                },
            )
        }
        return normalized
    }
}

enum class DashboardGoalType(
    val key: String,
) {
    CHARACTER("character"),
    ARTIFACTS("artifacts"),
    ;

    companion object {
        fun fromKey(key: String): DashboardGoalType? = entries.find { it.key == key }
    }
}

data class DashboardGoalSelection(
    val characterKey: String,
    val type: DashboardGoalType,
)
