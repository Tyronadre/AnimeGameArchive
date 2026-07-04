package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.ArtifactOptimizerCustomProfile
import org.springframework.data.jpa.repository.JpaRepository

interface ArtifactOptimizerCustomProfileRepository :
    JpaRepository<ArtifactOptimizerCustomProfile, Long> {
    fun findAllByUser_IdAndCharacterKeyOrderByProfileNameAsc(
        userId: Long,
        characterKey: String,
    ): List<ArtifactOptimizerCustomProfile>

    fun findByIdAndUser_IdAndCharacterKey(
        id: Long,
        userId: Long,
        characterKey: String,
    ): ArtifactOptimizerCustomProfile?

    fun findByUser_IdAndCharacterKeyAndProfileNameIgnoreCase(
        userId: Long,
        characterKey: String,
        profileName: String,
    ): ArtifactOptimizerCustomProfile?
}
