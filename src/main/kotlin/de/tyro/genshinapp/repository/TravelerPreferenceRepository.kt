package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.TravelerPreference
import org.springframework.data.repository.CrudRepository

interface TravelerPreferenceRepository : CrudRepository<TravelerPreference, Long> {
    fun findByUser_Id(userId: Long): TravelerPreference?
}
