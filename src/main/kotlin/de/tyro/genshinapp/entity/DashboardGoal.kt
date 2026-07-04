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
    name = "dashboard_goals",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_dashboard_goals_user_character_type",
            columnNames = ["user_id", "character_key", "goal_type"],
        ),
    ],
)
open class DashboardGoal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    open lateinit var user: User

    @Column(name = "character_key", nullable = false, length = 100)
    open var characterKey: String = ""

    @Column(name = "goal_type", nullable = false, length = 24)
    open var goalType: String = ""
}
