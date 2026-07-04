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
import org.hibernate.annotations.ColumnDefault

@Entity
@Table(
    name = "artifact_optimizer_custom_profiles",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_artifact_optimizer_custom_user_character_name",
            columnNames = ["user_id", "character_key", "profile_name"],
        ),
    ],
)
open class ArtifactOptimizerCustomProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    open lateinit var user: User

    @Column(name = "character_key", nullable = false, length = 100)
    open var characterKey: String = ""

    @Column(name = "profile_name", nullable = false, length = 60)
    open var profileName: String = ""

    @Column(name = "base_profile_key", nullable = false, length = 32)
    open var baseProfileKey: String = "attack"

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

    @Column(name = "additional_stats", nullable = false, length = 1000)
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
