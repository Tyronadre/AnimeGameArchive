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
import org.hibernate.annotations.ColumnDefault

@Entity
@Table(
    name = "character_targets",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_character_targets_user_character",
            columnNames = ["user_id", "character_key"],
        ),
    ],
)
open class CharacterTarget {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    open lateinit var user: User

    @Column(name = "character_key", nullable = false, length = 100)
    open var characterKey: String = ""

    @Column
    open var owned: Boolean? = null

    @Column(name = "current_level")
    open var currentLevel: Int? = null

    @Column(name = "current_ascension")
    open var currentAscension: Int? = null

    @Column(name = "current_constellation")
    open var currentConstellation: Int? = null

    @Column(name = "current_normal_talent")
    open var currentNormalTalent: Int? = null

    @Column(name = "current_skill_talent")
    open var currentSkillTalent: Int? = null

    @Column(name = "current_burst_talent")
    open var currentBurstTalent: Int? = null

    @Column(name = "additional_stats", nullable = false, length = 2000)
    @ColumnDefault("''")
    open var additionalStats: String = ""

    @Column(nullable = false)
    open var targetLevel: Int = 80

    @Column(nullable = false)
    open var targetAscension: Int = 6

    @Column(nullable = false)
    open var targetNormalTalent: Int = 9

    @Column(nullable = false)
    open var targetSkillTalent: Int = 9

    @Column(nullable = false)
    open var targetBurstTalent: Int = 9
}
