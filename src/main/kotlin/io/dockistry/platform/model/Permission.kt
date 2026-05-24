package io.dockistry.platform.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "permissions")
class Permission(
    @Column(name = "permission_key")
    val key: String,
    val description: String
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    @ManyToMany(mappedBy = "permissions")
    val roles: MutableSet<Role> = mutableSetOf()
}
