package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.CharacterTarget
import org.springframework.data.jpa.repository.JpaRepository

interface CharacterTargetRepository : JpaRepository<CharacterTarget, Long> {
    fun findByUser_IdAndCharacterKey(userId: Long, characterKey: String): CharacterTarget?

    fun findAllByUser_Id(userId: Long): List<CharacterTarget>
}
