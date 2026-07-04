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
    name = "character_weapon_targets",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_character_weapon_targets_user_character",
            columnNames = ["user_id", "character_key"],
        ),
    ],
)
open class CharacterWeaponTarget {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    open lateinit var user: User

    @Column(name = "character_key", nullable = false, length = 100)
    open var characterKey: String = ""

    @Column(name = "weapon_key", nullable = false, length = 100)
    open var weaponKey: String = ""

    @Column(name = "target_level", nullable = false)
    open var targetLevel: Int = 90
}
