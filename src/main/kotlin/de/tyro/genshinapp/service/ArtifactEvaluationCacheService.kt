package de.tyro.genshinapp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.tyro.genshinapp.entity.ArtifactEvaluationCacheEntry
import de.tyro.genshinapp.repository.ArtifactEvaluationCacheRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ArtifactEvaluationCacheService(
    private val repository: ArtifactEvaluationCacheRepository,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun getOrCompute(
        cacheKey: String,
        compute: () -> ArtifactEvaluation,
    ): ArtifactEvaluation =
        find(cacheKey) ?: compute().also { evaluation ->
            runCatching {
                save(cacheKey, evaluation)
            }.onFailure { error ->
                if (error !is DataIntegrityViolationException) {
                    logger.debug(
                        "Artifact evaluation cache write failed for key {}",
                        cacheKey,
                        error,
                    )
                }
            }
        }

    @Transactional(readOnly = true)
    fun find(cacheKey: String): ArtifactEvaluation? =
        repository.findByCacheKey(cacheKey)?.let { entry ->
            runCatching {
                objectMapper.readValue<ArtifactEvaluation>(entry.evaluationJson)
            }.getOrNull()
        }

    @Transactional
    fun save(cacheKey: String, evaluation: ArtifactEvaluation) {
        val now = Instant.now()
        val entity = ArtifactEvaluationCacheEntry().also {
            it.cacheKey = cacheKey
            it.score = evaluation.score
            it.grade = evaluation.grade
            it.evaluationJson = objectMapper.writeValueAsString(evaluation)
            it.createdAt = now
            it.updatedAt = now
        }
        runCatching {
            repository.save(entity)
        }.recoverCatching { error ->
            if (error is DataIntegrityViolationException) {
                repository.findByCacheKey(cacheKey)?.also { existing ->
                    existing.score = evaluation.score
                    existing.grade = evaluation.grade
                    existing.evaluationJson = entity.evaluationJson
                    existing.updatedAt = now
                    repository.save(existing)
                }
            } else {
                throw error
            }
        }.getOrThrow()
    }
}
