package io.dockistry.platform.model

import jakarta.persistence.CascadeType
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "roles")
class Role(
    val name: String,
    val description: String,
    val isSystem: Boolean
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    @ManyToMany
    @JoinTable(
        name = "role_permissions",
        joinColumns = [JoinColumn(name = "role_id")],
        inverseJoinColumns = [JoinColumn(name = "permission_id")]
    )
    val permissions: MutableSet<Permission> = mutableSetOf()

    @OneToMany(
        mappedBy = "role",
        cascade = [CascadeType.ALL],
        orphanRemoval = true
    )
    val roleBindings: MutableSet<RoleBinding> = mutableSetOf()
}
