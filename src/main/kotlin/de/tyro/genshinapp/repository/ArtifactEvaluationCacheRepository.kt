package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.ArtifactEvaluationCacheEntry
import org.springframework.data.jpa.repository.JpaRepository

interface ArtifactEvaluationCacheRepository : JpaRepository<ArtifactEvaluationCacheEntry, Long> {
    fun findByCacheKey(cacheKey: String): ArtifactEvaluationCacheEntry?
}
