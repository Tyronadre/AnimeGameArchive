package de.tyro.genshinapp.service

import de.tyro.genshinapp.entity.CharacterTarget
import de.tyro.genshinapp.model.CharacterProgress
import de.tyro.genshinapp.model.CharacterProgressForm
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.repository.CharacterTargetRepository
import de.tyro.genshinapp.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CharacterTargetService(
    private val targetRepository: CharacterTargetRepository,
    private val userRepository: UserRepository,
) {
    @Transactional(readOnly = true)
    fun find(userId: Long, characterKey: String): CharacterTargetValues? =
        targetRepository.findByUser_IdAndCharacterKey(
            userId,
            GoodKeyNormalizer.normalize(characterKey),
        )?.toValues()

    @Transactional(readOnly = true)
    fun ownershipOverrides(userId: Long): Map<String, Boolean> =
        targetRepository.findAllByUser_Id(userId)
            .mapNotNull { target ->
                target.owned?.let { target.characterKey to it }
            }
            .toMap()

    @Transactional(readOnly = true)
    fun findAll(userId: Long): Map<String, CharacterTargetValues> =
        targetRepository.findAllByUser_Id(userId)
            .associate { it.characterKey to it.toValues() }

    @Transactional
    fun save(
        userId: Long,
        characterKey: String,
        progress: CharacterProgress,
    ): CharacterTargetValues {
        val normalizedKey = GoodKeyNormalizer.normalize(characterKey)
        require(normalizedKey.isNotBlank()) { "Invalid character key" }

        val target = findOrCreate(userId, normalizedKey)
        target.owned = progress.owned
        target.currentLevel = progress.level
        target.currentAscension = progress.ascension
        target.currentConstellation = progress.constellation
        target.currentNormalTalent = progress.normalTalent
        target.currentSkillTalent = progress.skillTalent
        target.currentBurstTalent = progress.burstTalent
        target.targetLevel = progress.targetLevel
        target.targetAscension = progress.targetAscension
        target.targetNormalTalent = progress.targetNormalTalent
        target.targetSkillTalent = progress.targetSkillTalent
        target.targetBurstTalent = progress.targetBurstTalent
        return targetRepository.save(target).toValues()
    }

    @Transactional
    fun saveAdditionalStats(
        userId: Long,
        characterKey: String,
        additionalStats: Map<String, Double>,
    ): CharacterTargetValues {
        val normalizedKey = GoodKeyNormalizer.normalize(characterKey)
        require(normalizedKey.isNotBlank()) { "Invalid character key" }
        val target = findOrCreate(userId, normalizedKey)
        target.additionalStats = encodeStats(additionalStats)
        return targetRepository.save(target).toValues()
    }

    private fun findOrCreate(userId: Long, normalizedKey: String): CharacterTarget =
        targetRepository.findByUser_IdAndCharacterKey(userId, normalizedKey)
            ?: CharacterTarget().also {
                it.user = userRepository.findById(userId)
                    .orElseThrow { IllegalArgumentException("User not found") }
                it.characterKey = normalizedKey
            }

    private fun CharacterTarget.toValues(): CharacterTargetValues =
        CharacterTargetValues(
            owned = owned,
            currentLevel = currentLevel,
            currentAscension = currentAscension,
            currentConstellation = currentConstellation,
            currentNormalTalent = currentNormalTalent,
            currentSkillTalent = currentSkillTalent,
            currentBurstTalent = currentBurstTalent,
            additionalStats = parseStats(additionalStats),
            targetLevel = targetLevel,
            targetAscension = targetAscension,
            targetNormalTalent = targetNormalTalent,
            targetSkillTalent = targetSkillTalent,
            targetBurstTalent = targetBurstTalent,
        )

    private fun encodeStats(stats: Map<String, Double>): String =
        stats.entries
            .filter { (key, value) -> key.isNotBlank() && value.isFinite() && value != 0.0 }
            .joinToString(";") { (key, value) -> "$key:$value" }

    private fun parseStats(value: String): Map<String, Double> =
        value.split(';').mapNotNull { entry ->
            val key = entry.substringBefore(':')
            val statValue = entry.substringAfter(':', "").toDoubleOrNull()
            if (key.isBlank() || statValue == null || !statValue.isFinite() || statValue == 0.0) {
                null
            } else {
                key to statValue
            }
        }.toMap()
}

data class CharacterTargetValues(
    val owned: Boolean?,
    val currentLevel: Int?,
    val currentAscension: Int?,
    val currentConstellation: Int?,
    val currentNormalTalent: Int?,
    val currentSkillTalent: Int?,
    val currentBurstTalent: Int?,
    val additionalStats: Map<String, Double>,
    val targetLevel: Int,
    val targetAscension: Int,
    val targetNormalTalent: Int,
    val targetSkillTalent: Int,
    val targetBurstTalent: Int,
) {
    fun applyTo(form: CharacterProgressForm) {
        owned?.let { form.owned = it }
        currentLevel?.let { form.level = it }
        currentAscension?.let { form.ascension = it }
        currentConstellation?.let { form.constellation = it }
        currentNormalTalent?.let { form.normalTalent = it }
        currentSkillTalent?.let { form.skillTalent = it }
        currentBurstTalent?.let { form.burstTalent = it }
        form.targetLevel = targetLevel
        form.targetAscension = targetAscension
        form.targetNormalTalent = targetNormalTalent
        form.targetSkillTalent = targetSkillTalent
        form.targetBurstTalent = targetBurstTalent
    }
}
