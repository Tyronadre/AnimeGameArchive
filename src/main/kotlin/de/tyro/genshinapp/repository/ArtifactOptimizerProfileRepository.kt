package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.ArtifactOptimizerProfile
import org.springframework.data.jpa.repository.JpaRepository

interface ArtifactOptimizerProfileRepository : JpaRepository<ArtifactOptimizerProfile, Long> {
    fun findByUser_IdAndCharacterKey(
        userId: Long,
        characterKey: String,
    ): ArtifactOptimizerProfile?
}
