package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.PlayerWeaponInstance
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PlayerWeaponInstanceRepository : JpaRepository<PlayerWeaponInstance, Long> {
    fun findAllByUser_IdOrderByImportPositionAscIdAsc(userId: Long): List<PlayerWeaponInstance>

    fun findByIdAndUser_Id(id: Long, userId: Long): PlayerWeaponInstance?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PlayerWeaponInstance instance where instance.user.id = :userId")
    fun deleteAllForUser(@Param("userId") userId: Long): Int
}
