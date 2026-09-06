package de.tyro.genshinapp.entity

import de.tyro.genshinapp.model.FightProperty
import jakarta.persistence.*

@Entity
@Table(name = "game_character_ascension_stat", uniqueConstraints = [
    UniqueConstraint(name = "uk_character_ascension_stat", columnNames = ["character_id", "ascension_phase", "stat_type"]),
])
class GameCharacterAscensionStat {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    lateinit var character: GameCharacter

    @Column(name = "ascension_phase", nullable = false)
    var phase: Int = 0

    @Enumerated(EnumType.STRING)
    @Column(name = "stat_type", nullable = false, length = 96)
    lateinit var stat: FightProperty

    @Column(name = "stat_bonus", nullable = false)
    var bonus: Double = 0.0
}
