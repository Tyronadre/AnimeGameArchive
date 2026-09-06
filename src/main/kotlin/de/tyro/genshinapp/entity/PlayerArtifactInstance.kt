package de.tyro.genshinapp.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "player_artifact_inventory")
class PlayerArtifactInventory {
    @Id
    @Column(name = "user_id")
    var userId: Long = 0

    @Column(nullable = false)
    var initializedAt: Instant = Instant.now()
}

@Entity
@Table(name = "player_artifact_instance", uniqueConstraints = [
    UniqueConstraint(name = "uk_player_artifact_user_position", columnNames = ["user_id", "import_position"]),
], indexes = [Index(name = "idx_player_artifact_equipped", columnList = "user_id,location")])
class PlayerArtifactInstance {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User

    @Column(name = "import_position", nullable = false)
    var importPosition: Int = 0
    @Column(nullable = false, length = 100)
    var setKey: String = ""
    @Column(nullable = false, length = 16)
    var slotKey: String = ""
    @Column(length = 96)
    var location: String? = null
    var level: Int = 0
    var rarity: Int = 5
    @Column(nullable = false, length = 48)
    var mainStatKey: String = ""
    var locked: Boolean = false
    var totalRolls: Int? = null
    var astralMark: Boolean = false
    var elixirCrafted: Boolean = false
    @Lob @Column(nullable = false)
    var substatsJson: String = "[]"
    @Lob @Column(name = "unactivated_substats_json")
    var unactivatedSubstatsJson: String? = null
}
