package de.tyro.genshinapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "artifact_evaluation_cache",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_artifact_evaluation_cache_key",
            columnNames = ["cache_key"],
        ),
    ],
)
open class ArtifactEvaluationCacheEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    open var id: Long? = null

    @Column(name = "cache_key", nullable = false, length = 64)
    open var cacheKey: String = ""

    @Column(nullable = false)
    open var score: Double = 0.0

    @Column(nullable = false, length = 2)
    open var grade: String = ""

    @Lob
    @Column(name = "evaluation_json", nullable = false)
    open var evaluationJson: String = ""

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.EPOCH

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant = Instant.EPOCH
}
