package de.tyro.genshinapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table

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

    @Lob
    @Column(nullable = false)
    var imageUrlsJson: String = "{}"

    @Lob
    @Column(nullable = false)
    var remoteImageUrlsJson: String = "{}"

    @Lob
    @Column(nullable = false)
    var ascensionCostsJson: String = "{}"

    @Lob
    @Column(nullable = false)
    var talentCostsJson: String = "{}"

    @Lob
    var talentsJson: String? = null
}
