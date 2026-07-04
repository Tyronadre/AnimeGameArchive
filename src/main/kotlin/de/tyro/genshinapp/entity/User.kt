package de.tyro.genshinapp.entity

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "app_users",
    uniqueConstraints = [UniqueConstraint(name = "uk_app_users_email", columnNames = ["email"])],
)
open class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    open var id: Long? = null

    @Column(nullable = false, length = 60)
    open var name: String = ""

    @Column(nullable = false, length = 254)
    open var email: String = ""

    @Column(nullable = false, length = 60)
    @JsonIgnore
    open var passwordHash: String = ""
}
