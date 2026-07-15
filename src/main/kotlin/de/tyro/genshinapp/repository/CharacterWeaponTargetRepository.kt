package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.CharacterWeaponTarget
import org.springframework.data.jpa.repository.JpaRepository

interface CharacterWeaponTargetRepository : JpaRepository<CharacterWeaponTarget, Long> {
    fun findByUser_IdAndCharacterKey(
        userId: Long,
        characterKey: String,
    ): CharacterWeaponTarget?

    fun findAllByUser_Id(userId: Long): List<CharacterWeaponTarget>
}
