package de.tyro.genshinapp.entity

import de.tyro.genshinapp.model.FightProperty
import jakarta.persistence.*

@Entity
@Table(name = "game_character_base_stat", uniqueConstraints = [
    UniqueConstraint(name = "uk_character_base_stat", columnNames = ["character_id", "stat_type"]),
])
class GameCharacterBaseStat {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    lateinit var character: GameCharacter

    @Enumerated(EnumType.STRING)
    @Column(name = "stat_type", nullable = false, length = 96)
    lateinit var stat: FightProperty

    @Column(name = "stat_value", nullable = false)
    var value: Double = 0.0

    @Column(name = "growth_curve", length = 64)
    var growthCurve: String? = null
}
