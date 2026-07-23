package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.GameWeapon
import org.springframework.data.jpa.repository.JpaRepository

interface GameWeaponRepository : JpaRepository<GameWeapon, Long> {
    fun findByKey(key: String): GameWeapon?

    fun findAllByOrderByNameAsc(): List<GameWeapon>
}
