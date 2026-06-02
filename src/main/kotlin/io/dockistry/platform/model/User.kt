package io.dockistry.platform.model

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

@Entity
@Table(name = "users")
class User(
    @Column(nullable = false, unique = true)
    val username: String,

    @Column(nullable = false, unique = true)
    val email: String,

    @Column(nullable = false)
    var passwordHash: String
) {
    @Id
    lateinit var id: UUID

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
