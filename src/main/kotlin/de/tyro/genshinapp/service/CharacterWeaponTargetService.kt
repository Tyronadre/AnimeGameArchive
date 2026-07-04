package de.tyro.genshinapp.service

import de.tyro.genshinapp.entity.CharacterWeaponTarget
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.repository.CharacterWeaponTargetRepository
import de.tyro.genshinapp.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CharacterWeaponTargetService(
    private val targetRepository: CharacterWeaponTargetRepository,
    private val userRepository: UserRepository,
) {
    @Transactional(readOnly = true)
    fun find(userId: Long, characterKey: String): SavedWeaponTarget? =
        targetRepository.findByUser_IdAndCharacterKey(
            userId,
            GoodKeyNormalizer.normalize(characterKey),
        )?.toValues()

    @Transactional
    fun save(
        userId: Long,
        characterKey: String,
        weaponKey: String,
        currentLevel: Int,
        requestedTargetLevel: Int,
        maxLevel: Int = 90,
    ): SavedWeaponTarget {
        val normalizedCharacterKey = GoodKeyNormalizer.normalize(characterKey)
        val normalizedWeaponKey = GoodKeyNormalizer.normalize(weaponKey)
        require(normalizedCharacterKey.isNotBlank() && normalizedWeaponKey.isNotBlank())
        val targetLevel = WeaponPlanningService.validTargetLevels(currentLevel, maxLevel)
            .filter { it >= requestedTargetLevel }
            .minOrNull()
            ?: WeaponPlanningService.validTargetLevels(currentLevel, maxLevel).last()
        val target = targetRepository.findByUser_IdAndCharacterKey(
            userId,
            normalizedCharacterKey,
        ) ?: CharacterWeaponTarget().also {
            it.user = userRepository.findById(userId)
                .orElseThrow { IllegalArgumentException("User not found") }
            it.characterKey = normalizedCharacterKey
        }
        target.weaponKey = normalizedWeaponKey
        target.targetLevel = targetLevel
        return targetRepository.save(target).toValues()
    }

    @Transactional
    fun delete(userId: Long, characterKey: String) {
        targetRepository.findByUser_IdAndCharacterKey(
            userId,
            GoodKeyNormalizer.normalize(characterKey),
        )?.let(targetRepository::delete)
    }

    private fun CharacterWeaponTarget.toValues(): SavedWeaponTarget =
        SavedWeaponTarget(
            weaponKey = weaponKey,
            targetLevel = targetLevel,
        )
}

data class SavedWeaponTarget(
    val weaponKey: String,
    val targetLevel: Int,
)
