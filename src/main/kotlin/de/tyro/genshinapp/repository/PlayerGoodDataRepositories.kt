package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.PlayerGoodCharacterEntity
import de.tyro.genshinapp.entity.PlayerGoodMaterialEntity
import de.tyro.genshinapp.entity.PlayerGoodSnapshotEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface PlayerGoodSnapshotRepository : JpaRepository<PlayerGoodSnapshotEntity, Long>

interface PlayerGoodCharacterRepository : JpaRepository<PlayerGoodCharacterEntity, Long> {
    fun findAllByUser_IdOrderByImportPositionAsc(userId: Long): List<PlayerGoodCharacterEntity>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PlayerGoodCharacterEntity character where character.user.id = :userId")
    fun deleteAllForUser(userId: Long)
}

interface PlayerGoodMaterialRepository : JpaRepository<PlayerGoodMaterialEntity, Long> {
    fun findAllByUser_IdOrderByImportPositionAsc(userId: Long): List<PlayerGoodMaterialEntity>

    fun findByUser_IdAndMaterialKey(userId: Long, materialKey: String): PlayerGoodMaterialEntity?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PlayerGoodMaterialEntity material where material.user.id = :userId")
    fun deleteAllForUser(userId: Long)
}
