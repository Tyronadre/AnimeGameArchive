package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.GameCharacterStats
import org.springframework.data.repository.CrudRepository

interface GameCharacterStatsRepository : CrudRepository<GameCharacterStats, Long> {
}
