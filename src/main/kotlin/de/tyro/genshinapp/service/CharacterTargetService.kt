package de.tyro.genshinapp.service

import de.tyro.genshinapp.entity.CharacterTarget
import de.tyro.genshinapp.model.CharacterProgress
import de.tyro.genshinapp.model.CharacterProgressForm
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.TravelerIdentity
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
        findTarget(userId, characterKey)?.toValues()

    @Transactional(readOnly = true)
    fun ownershipOverrides(userId: Long): Map<String, Boolean> =
        canonicalTargets(userId)
            .mapNotNull { (characterKey, target) ->
                target.owned?.let {
                    characterKey to it
                }
            }
            .toMap()

    @Transactional(readOnly = true)
    fun findAll(userId: Long): Map<String, CharacterTargetValues> =
        canonicalTargets(userId).mapValues { (_, target) -> target.toValues() }

    @Transactional
    fun save(
        userId: Long,
        characterKey: String,
        progress: CharacterProgress,
    ): CharacterTargetValues {
        val normalizedKey = TravelerIdentity.canonicalCharacterKey(characterKey)
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
    fun saveShared(
        userId: Long,
        characterKey: String,
        progress: CharacterProgress,
    ): CharacterTargetValues {
        val normalizedKey = TravelerIdentity.canonicalCharacterKey(characterKey)
        require(normalizedKey.isNotBlank()) { "Invalid character key" }

        val target = findOrCreate(userId, normalizedKey)
        target.owned = progress.owned
        target.currentLevel = progress.level
        target.currentAscension = progress.ascension
        target.targetLevel = progress.targetLevel
        target.targetAscension = progress.targetAscension
        return targetRepository.save(target).toValues()
    }

    @Transactional
    fun saveAdditionalStats(
        userId: Long,
        characterKey: String,
        additionalStats: Map<String, Double>,
    ): CharacterTargetValues {
        val normalizedKey = TravelerIdentity.canonicalCharacterKey(characterKey)
        require(normalizedKey.isNotBlank()) { "Invalid character key" }
        val target = findOrCreate(userId, normalizedKey)
        target.additionalStats = encodeStats(additionalStats)
        return targetRepository.save(target).toValues()
    }

    private fun findOrCreate(userId: Long, normalizedKey: String): CharacterTarget =
        findTarget(userId, normalizedKey)
            ?: CharacterTarget().also {
                it.user = userRepository.findById(userId)
                    .orElseThrow { IllegalArgumentException("User not found") }
                it.characterKey = normalizedKey
            }

    private fun findTarget(userId: Long, characterKey: String): CharacterTarget? {
        val normalizedKey = TravelerIdentity.canonicalCharacterKey(characterKey)
        return targetRepository.findByUser_IdAndCharacterKey(userId, normalizedKey)
            ?: if (normalizedKey == TravelerIdentity.KEY) {
                targetRepository.findAllByUser_Id(userId)
                    .firstOrNull { TravelerIdentity.isTraveler(it.characterKey) }
            } else {
                null
            }
    }

    private fun canonicalTargets(userId: Long): Map<String, CharacterTarget> =
        targetRepository.findAllByUser_Id(userId)
            .groupBy { TravelerIdentity.canonicalCharacterKey(it.characterKey) }
            .mapValues { (canonicalKey, targets) ->
                targets.firstOrNull {
                    GoodKeyNormalizer.normalize(it.characterKey) == canonicalKey
                } ?: targets.first()
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
    fun applySharedTo(form: CharacterProgressForm) {
        owned?.let { form.owned = it }
        currentLevel?.let { form.level = it }
        currentAscension?.let { form.ascension = it }
        form.targetLevel = targetLevel
        form.targetAscension = targetAscension
    }

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
