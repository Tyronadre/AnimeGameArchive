package de.tyro.genshinapp.entity

import de.tyro.genshinapp.model.WeaponImageType
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
    name = "game_weapon_image",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_weapon_image_type",
            columnNames = ["weapon_id", "image_type"],
        ),
    ],
)
class GameWeaponImage {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(nullable = false)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "weapon_id", nullable = false)
    lateinit var weapon: GameWeapon

    @Enumerated(EnumType.STRING)
    @Column(name = "image_type", nullable = false, length = 32)
    var imageType: WeaponImageType = WeaponImageType.ICON

    @Column(name = "local_url", length = 1024)
    var localUrl: String? = null

    @Column(name = "remote_url", length = 4096)
    var remoteUrl: String? = null
}
