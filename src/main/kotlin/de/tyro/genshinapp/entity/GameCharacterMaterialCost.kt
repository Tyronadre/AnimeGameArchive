package de.tyro.genshinapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
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
    name = "game_character_material_cost",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_character_cost_material",
            columnNames = ["character_id", "cost_type", "cost_level", "material_id"],
        ),
    ],
)
class GameCharacterMaterialCost {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(nullable = false)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    lateinit var character: GameCharacter

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    lateinit var material: Material

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_type", nullable = false, length = 16)
    var costType: GameCharacterCostType = GameCharacterCostType.ASCENSION

    @Column(name = "cost_level", nullable = false)
    var level: Int = 0

    @Column(nullable = false)
    var amount: Long = 0

    @Column(name = "material_order", nullable = false)
    var materialOrder: Int = 0
}

enum class GameCharacterCostType {
    ASCENSION,
    TALENT,
}
