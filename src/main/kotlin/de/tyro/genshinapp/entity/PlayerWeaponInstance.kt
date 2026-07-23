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

@Entity
@Table(
    name = "player_weapon_instance",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_player_weapon_instance_user_position",
            columnNames = ["user_id", "import_position"],
        ),
    ],
)
class PlayerWeaponInstance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "weapon_id", nullable = false)
    lateinit var weapon: GameWeapon

    @Column(name = "import_position", nullable = false)
    var importPosition: Int = 0

    @Column(nullable = false)
    var level: Int = 1

    @Column(nullable = false)
    var ascension: Int = 0

    @Column(nullable = false)
    var refinement: Int = 1

    @Column(length = 96)
    var location: String? = null

    @Column(nullable = false)
    var locked: Boolean = false
}
