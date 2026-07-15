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

@Entity
@Table(
    name = "traveler_element_progress",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_traveler_element_progress_user_element",
            columnNames = ["user_id", "element"],
        ),
    ],
)
open class TravelerElementProgress {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    open lateinit var user: User

    @Column(nullable = false, length = 12)
    open var element: String = ""

    @Column(name = "current_constellation")
    open var currentConstellation: Int? = null

    @Column(name = "current_normal_talent")
    open var currentNormalTalent: Int? = null

    @Column(name = "current_skill_talent")
    open var currentSkillTalent: Int? = null

    @Column(name = "current_burst_talent")
    open var currentBurstTalent: Int? = null

    @Column(name = "target_normal_talent", nullable = false)
    open var targetNormalTalent: Int = 9

    @Column(name = "target_skill_talent", nullable = false)
    open var targetSkillTalent: Int = 9

    @Column(name = "target_burst_talent", nullable = false)
    open var targetBurstTalent: Int = 9
}
