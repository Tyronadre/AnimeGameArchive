package de.tyro.genshinapp.entity

import de.tyro.genshinapp.model.FightProperty
import jakarta.persistence.*

/** Level growth checkpoints with ascension increases removed. */
@Entity
@Table(name = "game_character_level_stat", uniqueConstraints = [
    UniqueConstraint(name = "uk_character_level_stat", columnNames = ["character_id", "character_level", "stat_type"]),
])
class GameCharacterLevelStat {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    lateinit var character: GameCharacter

    @Column(name = "character_level", nullable = false)
    var level: Int = 1

    @Enumerated(EnumType.STRING)
    @Column(name = "stat_type", nullable = false, length = 96)
    lateinit var stat: FightProperty

    @Column(name = "growth_value", nullable = false)
    var growth: Double = 0.0
}
