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
    name = "game_character_progression",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_character_progression_level",
            columnNames = ["character_id", "character_level"],
        ),
    ],
)
class GameCharacterProgression {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(nullable = false)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    lateinit var character: GameCharacter

    @Column(name = "character_level", nullable = false)
    var level: Int = 0

    var hpBeforeAscension: Double? = null
    var hpAfterAscension: Double? = null
    var attackBeforeAscension: Double? = null
    var attackAfterAscension: Double? = null
    var defenseBeforeAscension: Double? = null
    var defenseAfterAscension: Double? = null
    var secondaryStatBeforeAscension: Double? = null
    var secondaryStatAfterAscension: Double? = null
}
