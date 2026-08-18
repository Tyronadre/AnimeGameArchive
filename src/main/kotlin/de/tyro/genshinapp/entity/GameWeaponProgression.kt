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
    name = "game_weapon_progression",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_weapon_progression_level",
            columnNames = ["weapon_id", "weapon_level"],
        ),
    ],
)
class GameWeaponProgression {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(nullable = false)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "weapon_id", nullable = false)
    lateinit var weapon: GameWeapon

    @Column(name = "weapon_level", nullable = false)
    var level: Int = 0

    @Column(name = "attack_before_ascension")
    var attackBeforeAscension: Double? = null

    @Column(name = "attack_after_ascension")
    var attackAfterAscension: Double? = null

    @Column(name = "secondary_stat")
    var secondaryStat: Double? = null

}
