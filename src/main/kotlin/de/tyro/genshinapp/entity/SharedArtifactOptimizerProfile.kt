package de.tyro.genshinapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import org.hibernate.annotations.ColumnDefault

@Entity
@Table(
    name = "shared_artifact_optimizer_profiles",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_shared_artifact_optimizer_profiles_token",
            columnNames = ["share_token"],
        ),
    ],
)
open class SharedArtifactOptimizerProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    open lateinit var createdBy: User

    @Column(name = "share_token", nullable = false, length = 40)
    open var shareToken: String = ""

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()

    @Column(name = "profile_key", nullable = false, length = 32)
    open var profileKey: String = "attack"

    @Column(name = "custom_targets", nullable = false)
    open var customTargets: Boolean = false

    @Column(name = "sands_main", length = 32)
    open var sandsMain: String? = null

    @Column(name = "goblet_main", length = 32)
    open var gobletMain: String? = null

    @Column(name = "circlet_main", length = 32)
    open var circletMain: String? = null

    @Column(name = "substat_keys", nullable = false, length = 500)
    open var substatKeys: String = ""

    @Column(name = "minimum_targets", nullable = false, length = 1000)
    @ColumnDefault("''")
    open var minimumTargets: String = ""

    @Column(name = "maximum_targets", nullable = false, length = 1000)
    @ColumnDefault("''")
    open var maximumTargets: String = ""

    @Column(name = "additional_crit_rate", nullable = false)
    @ColumnDefault("0")
    open var additionalCritRate: Double = 0.0

    @Column(name = "additional_stats", nullable = false, length = 2000)
    @ColumnDefault("''")
    open var additionalStats: String = ""

    @Column(name = "set_mode", nullable = false, length = 16)
    open var setMode: String = "current"

    @Column(name = "first_set_key", length = 100)
    open var firstSetKey: String? = null

    @Column(name = "first_set_count")
    open var firstSetCount: Int? = null

    @Column(name = "second_set_key", length = 100)
    open var secondSetKey: String? = null

    @Column(name = "second_set_count")
    open var secondSetCount: Int? = null
}
