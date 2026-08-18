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
    name = "game_weapon_material_cost",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_weapon_cost_material",
            columnNames = ["weapon_id", "ascension_phase", "material_id"],
        ),
    ],
)
class GameWeaponMaterialCost {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(nullable = false)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "weapon_id", nullable = false)
    lateinit var weapon: GameWeapon

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    lateinit var material: Material

    @Column(name = "ascension_phase", nullable = false)
    var phase: Int = 0

    @Column(nullable = false)
    var amount: Long = 0

    @Column(name = "material_order", nullable = false)
    var materialOrder: Int = 0
}
