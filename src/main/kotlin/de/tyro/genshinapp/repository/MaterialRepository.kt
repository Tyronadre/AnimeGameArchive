package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.Material
import org.springframework.data.repository.CrudRepository
import org.springframework.data.jpa.repository.EntityGraph

interface MaterialRepository : CrudRepository<Material, Long> {
    @EntityGraph(attributePaths = ["hoyowikiEntry"])
    fun findByGameId(gameId: Int): Material?

    @EntityGraph(attributePaths = ["hoyowikiEntry"])
    fun findAllByOrderByNameAsc(): List<Material>

    @EntityGraph(attributePaths = ["hoyowikiEntry"])
    fun findAllByGameIdInOrderByNameAsc(gameIds: Collection<Int>): List<Material>

    @EntityGraph(attributePaths = ["hoyowikiEntry"])
    fun findAllByTypeInOrderByNameAsc(types: Collection<String>): List<Material>
}
