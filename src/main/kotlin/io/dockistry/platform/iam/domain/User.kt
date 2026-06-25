package io.dockistry.platform.iam.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.MapsId
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import java.util.UUID
import java.time.Instant

@Entity
@Table(name = "users")
class User(
    username: String,
    email: String,

    @Column(nullable = false)
    var passwordHash: String
) {
    @Id
    lateinit var id: UUID

    @Column(nullable = false, unique = true)
    var username: String = UserCredentialsValidator.validateUsername(username)
        set(value) {
            field = UserCredentialsValidator.validateUsername(value)
        }

    @Column(nullable = false, unique = true)
    var email: String = UserCredentialsValidator.validateEmail(email)
        set(value) {
            field = UserCredentialsValidator.validateEmail(value)
        }

    @Column(name = "email_verified_at")
    var emailVerifiedAt: Instant? = null

    @OneToOne(
        fetch = FetchType.LAZY,
        optional = false,
        cascade = [CascadeType.PERSIST]
    )
    @JoinColumn(name = "id")
    @MapsId
    lateinit var principal: Principal

    @ManyToMany
    @JoinTable(
        name = "group_members",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "group_id")]
    )
    val groups: MutableSet<Group> = mutableSetOf()
}
