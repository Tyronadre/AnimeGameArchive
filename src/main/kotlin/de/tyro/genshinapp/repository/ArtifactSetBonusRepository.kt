package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.ArtifactSetBonus
import org.springframework.data.jpa.repository.JpaRepository

interface ArtifactSetBonusRepository : JpaRepository<ArtifactSetBonus, Long> {
    fun findBySetKey(setKey: String): ArtifactSetBonus?
}
