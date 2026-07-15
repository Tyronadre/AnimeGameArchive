package de.tyro.genshinapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "traveler_preferences",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_traveler_preferences_user",
            columnNames = ["user_id"],
        ),
    ],
)
open class TravelerPreference {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    open var id: Long? = null

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    open lateinit var user: User

    @Column(nullable = false, length = 12)
    open var appearance: String = "aether"

    @Column(name = "active_element", length = 12)
    open var activeElement: String? = null
}
