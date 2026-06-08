package io.dockistry.platform.iam.domain

import io.dockistry.platform.projects.domain.Project
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "roles")
class Role(
    @Column(nullable = false)
    val name: String,

    @Column(nullable = false)
    val description: String,

    @Column(nullable = false, name = "is_system")
    val isSystem: Boolean,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    val project: Project? = null
) {
    init {
        validateOwnership()
    }

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

    @PrePersist
    @PreUpdate
    fun validateBeforeSave() {
        validateOwnership()
    }

    private fun validateOwnership() {
        require((isSystem && project == null) || (!isSystem && project != null)) {
            "Role must be either system-owned or project-owned"
        }
    }
}
