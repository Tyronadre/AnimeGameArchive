package de.tyro.genshinapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table

/** Legacy JSON archive retained for existing databases. No longer populated or read by stat services. */
@Entity
@Table(name = "build_stat_table")
class BuildStatTable {
    @Id
    @Column(length = 100)
    var tableKey: String = ""

    @Column(nullable = false, length = 500)
    var sourceUrl: String = ""

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    var dataJson: String = "{}"
}
