package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.TravelerElementProgress
import org.springframework.data.repository.CrudRepository

interface TravelerElementProgressRepository : CrudRepository<TravelerElementProgress, Long> {
    fun findByUser_IdAndElement(userId: Long, element: String): TravelerElementProgress?
}
