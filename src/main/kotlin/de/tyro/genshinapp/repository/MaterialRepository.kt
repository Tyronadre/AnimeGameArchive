package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.Material
import org.springframework.data.repository.CrudRepository

interface MaterialRepository : CrudRepository<Material, Long> {
    fun findByGameId(gameId: Int): Material?

    fun findAllByOrderByNameAsc(): List<Material>

    fun findAllByGameIdInOrderByNameAsc(gameIds: Collection<Int>): List<Material>

    fun findAllByTypeInOrderByNameAsc(types: Collection<String>): List<Material>
}
