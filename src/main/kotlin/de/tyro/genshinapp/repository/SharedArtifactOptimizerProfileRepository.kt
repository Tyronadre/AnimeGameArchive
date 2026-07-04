package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.SharedArtifactOptimizerProfile
import org.springframework.data.jpa.repository.JpaRepository

interface SharedArtifactOptimizerProfileRepository :
    JpaRepository<SharedArtifactOptimizerProfile, Long> {
    fun findByShareToken(shareToken: String): SharedArtifactOptimizerProfile?
}
