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

@Entity
@Table(name = "player_good_snapshot")
open class PlayerGoodSnapshotEntity {
    @Id
    @Column(name = "user_id")
    open var userId: Long = 0

    @Column(name = "format_version", nullable = false)
    open var formatVersion: Int = 3

    @Column(length = 120)
    open var source: String? = null

    @Column(name = "imported_at", nullable = false)
    open var importedAt: Instant = Instant.now()
}

@Entity
@Table(
    name = "player_good_character",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_player_good_character_user_position",
            columnNames = ["user_id", "import_position"],
        ),
    ],
)
open class PlayerGoodCharacterEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    open lateinit var user: User

    @Column(name = "import_position", nullable = false)
    open var importPosition: Int = 0

    @Column(name = "export_key", nullable = false, length = 96)
    open var exportKey: String = ""

    @Column(nullable = false)
    open var level: Int = 1

    @Column(nullable = false)
    open var constellation: Int = 0

    @Column(nullable = false)
    open var ascension: Int = 0

    @Column(name = "normal_talent", nullable = false)
    open var normalTalent: Int = 1

    @Column(name = "skill_talent", nullable = false)
    open var skillTalent: Int = 1

    @Column(name = "burst_talent", nullable = false)
    open var burstTalent: Int = 1
}

@Entity
@Table(
    name = "player_good_material",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_player_good_material_user_key",
            columnNames = ["user_id", "material_key"],
        ),
    ],
)
open class PlayerGoodMaterialEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    open lateinit var user: User

    @Column(name = "import_position", nullable = false)
    open var importPosition: Int = 0

    @Column(name = "material_key", nullable = false, length = 120)
    open var materialKey: String = ""

    @Column(name = "export_key", nullable = false, length = 120)
    open var exportKey: String = ""

    @Column(nullable = false)
    open var amount: Long = 0
}
