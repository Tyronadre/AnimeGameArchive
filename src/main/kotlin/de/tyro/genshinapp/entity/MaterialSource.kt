package de.tyro.genshinapp.entity

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "material_source",
    indexes = [
        Index(name = "idx_material_source_type_order", columnList = "source_type,display_order"),
    ],
)
class MaterialSource {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(nullable = false)
    var id: Long? = null

    @Column(name = "catalog_key", nullable = false, unique = true)
    var catalogKey: String = ""

    @Column(nullable = false)
    var name: String = ""

    @Column(name = "source_type", nullable = false)
    var sourceType: String = ""

    var region: String? = null

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0

    @OneToMany(mappedBy = "source", cascade = [CascadeType.ALL], orphanRemoval = true)
    var materials: MutableList<MaterialSourceMaterial> = mutableListOf()
}

@Entity
@Table(
    name = "material_source_material",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_material_source_material_role",
            columnNames = ["source_id", "material_id", "source_role"],
        ),
    ],
)
class MaterialSourceMaterial {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(nullable = false)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    lateinit var source: MaterialSource

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    lateinit var material: Material

    @Column(name = "source_role", nullable = false)
    var sourceRole: String = ""

    @Column(name = "family_order", nullable = false)
    var familyOrder: Int = 0

    @Column(name = "material_order", nullable = false)
    var materialOrder: Int = 0

    @Column(name = "schedule_key")
    var scheduleKey: String? = null
}
