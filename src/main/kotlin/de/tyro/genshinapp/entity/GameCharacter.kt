package de.tyro.genshinapp.entity

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import org.hibernate.annotations.BatchSize

@Entity
@Table(name = "game_character")
class GameCharacter {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(nullable = false)
    var id: Long? = null

    @Column(name = "catalog_key", nullable = false, unique = true, length = 96)
    var key: String = ""

    @Column(name = "game_id", nullable = false)
    var gameId: Long = 0

    @Column(nullable = false)
    var name: String = ""

    var title: String? = null

    @Lob
    var description: String? = null

    var weapon: String? = null

    var rarity: Int = 0

    var birthday: String? = null

    var element: String? = null

    var affiliation: String? = null

    var region: String? = null

    var constellation: String? = null

    var ascensionStatType: String? = null

    @Column(name = "image_resource_key", length = 96)
    var imageResourceKey: String? = null

    @Column(name = "talent_resource_key", length = 96)
    var talentResourceKey: String? = null

    @OneToMany(mappedBy = "character", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("imageType ASC")
    @BatchSize(size = 100)
    var images: MutableList<GameCharacterImage> = mutableListOf()

    @OneToMany(mappedBy = "character", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("costType ASC, level ASC, materialOrder ASC")
    @BatchSize(size = 100)
    var materialCosts: MutableList<GameCharacterMaterialCost> = mutableListOf()

    @OneToMany(mappedBy = "character", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    @BatchSize(size = 100)
    var talents: MutableList<GameCharacterTalent> = mutableListOf()
}
