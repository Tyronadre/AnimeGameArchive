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
    name = "artifact_set_bonus",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_artifact_set_bonus_set_key",
            columnNames = ["set_key"],
        ),
    ],
)
open class ArtifactSetBonus {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    open var id: Long? = null

    @Column(name = "set_key", nullable = false, length = 100)
    open var setKey: String = ""

    @Column(name = "set_name", nullable = false, length = 160)
    open var setName: String = ""

    @Column(name = "source_version", nullable = false)
    open var sourceVersion: Int = 0

    @Lob
    @Column(name = "bonuses_json", nullable = false)
    open var bonusesJson: String = "[]"

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant = Instant.EPOCH
}
