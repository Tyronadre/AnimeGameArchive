package de.tyro.genshinapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(name = "game_artifact_main_stat", uniqueConstraints = [UniqueConstraint(columnNames = ["rarity", "artifact_level", "stat_key"])])
class GameArtifactMainStat {
    @Id @Column(length = 80)
    var id: String = ""
    @Column(nullable = false)
    var rarity: Int = 0
    @Column(name = "artifact_level", nullable = false)
    var level: Int = 0
    @Column(nullable = false, length = 48)
    var statKey: String = ""
    @Column(name = "stat_value", nullable = false)
    var value: Double = 0.0
}
