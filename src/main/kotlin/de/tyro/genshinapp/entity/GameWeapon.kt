package de.tyro.genshinapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table

@Entity
@Table(name = "game_weapon")
class GameWeapon {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(nullable = false)
    var id: Long? = null

    @Column(name = "catalog_key", nullable = false, unique = true, length = 96)
    var key: String = ""

    @Column(nullable = false, length = 160)
    var name: String = ""

    @Column(nullable = false)
    var rarity: Int = 0

    @Column(name = "weapon_type", length = 64)
    var weaponType: String? = null

    @Column(name = "secondary_stat_type", length = 96)
    var secondaryStatType: String? = null

    @Column(name = "base_attack")
    var baseAttack: Double? = null

    @Column(name = "base_secondary_stat")
    var baseSecondaryStat: Double? = null

    @Lob
    var description: String? = null

    @Column(length = 96)
    var region: String? = null

    @Column(name = "obtain_method", length = 256)
    var obtainMethod: String? = null

    @Column(name = "release_version", length = 48)
    var releaseVersion: String? = null

    @Column(name = "passive_name", length = 200)
    var passiveName: String? = null

    @Lob
    @Column(name = "passive_description")
    var passiveDescription: String? = null

    @Lob
    var story: String? = null

    @Column(name = "image_url", length = 512)
    var imageUrl: String? = null

    @Column(name = "remote_image_url", length = 4096)
    var remoteImageUrl: String? = null

    @Column(name = "hoyolab_entry_id")
    var hoyolabEntryId: Long? = null

    @Column(name = "hoyolab_icon_url", length = 4096)
    var hoyolabIconUrl: String? = null

    @Column(name = "hoyolab_page_version", length = 64)
    var hoyolabPageVersion: String? = null

    @Column(name = "hoyolab_data_version")
    var hoyolabDataVersion: Int? = 0

    @Column(name = "full_image_url", length = 4096)
    var fullImageUrl: String? = null

    @Lob
    @Column(name = "gallery_images_json")
    var galleryImagesJson: String? = "[]"

    @Lob
    @Column(name = "hoyolab_ascension_json")
    var hoyolabAscensionJson: String? = "[]"

    @Lob
    @Column(name = "ascension_costs_json", nullable = false)
    var ascensionCostsJson: String = "{}"
}
