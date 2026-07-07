package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.GameCharacter
import org.springframework.data.repository.CrudRepository

interface GameCharacterRepository : CrudRepository<GameCharacter, Long> {
    fun findByKey(key: String): GameCharacter?

    fun findAllByOrderByNameAsc(): List<GameCharacter>
}
