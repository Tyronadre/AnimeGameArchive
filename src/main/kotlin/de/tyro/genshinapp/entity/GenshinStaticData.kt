package de.tyro.genshinapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "genshin_static_data",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_genshin_static_data_folder_key",
            columnNames = ["folder", "catalog_key"],
        ),
    ],
)
class GenshinStaticData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    var id: Long? = null

    @Column(nullable = false, length = 64)
    var folder: String = ""

    @Column(name = "catalog_key", nullable = false, length = 192)
    var catalogKey: String = ""

    @Column(nullable = false, length = 255)
    var name: String = ""

    @Column(name = "source_version", length = 32)
    var sourceVersion: String? = null

    @Column(name = "content_hash", nullable = false, length = 64)
    var contentHash: String = ""

    @Lob
    @Column(name = "source_json", nullable = false)
    var sourceJson: String = "{}"

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH
}
