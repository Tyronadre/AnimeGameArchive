package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.PlayerArtifactInstance
import de.tyro.genshinapp.entity.PlayerArtifactInventory
import org.springframework.data.jpa.repository.JpaRepository

interface PlayerArtifactInstanceRepository : JpaRepository<PlayerArtifactInstance, Long> {
    fun findAllByUser_IdOrderByImportPositionAsc(userId: Long): List<PlayerArtifactInstance>
}

interface PlayerArtifactInventoryRepository : JpaRepository<PlayerArtifactInventory, Long>
