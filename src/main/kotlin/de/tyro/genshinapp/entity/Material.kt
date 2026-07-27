package de.tyro.genshinapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(
    name = "material",
    indexes = [
        Index(name = "idx_material_type", columnList = "type"),
        Index(name = "idx_material_crafting_family", columnList = "crafting_family,crafting_tier"),
        Index(name = "idx_material_conversion_group", columnList = "conversion_group"),
    ],
)
class Material {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(nullable = false)
    var id: Long? = null

    @Column(name = "game_id", nullable = false, unique = true)
    var gameId: Int = 0

    @Column(nullable = false)
    var name: String = ""

    var type: String? = null

    @Column(name = "crafting_family")
    var craftingFamily: String? = null

    @Column(name = "crafting_tier")
    var craftingTier: Int? = null

    @Column(name = "conversion_group")
    var conversionGroup: String? = null
}
