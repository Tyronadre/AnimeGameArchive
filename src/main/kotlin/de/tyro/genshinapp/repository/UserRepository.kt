package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.User
import org.springframework.data.repository.CrudRepository

interface UserRepository : CrudRepository<User, Long> {

    fun findByEmailIgnoreCase(email: String): User?
}
