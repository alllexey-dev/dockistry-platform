package io.dockistry.platform.model

import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "role_bindings")
class RoleBinding {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    @ManyToOne(
        optional = false,
        fetch = FetchType.LAZY
    )
    @JoinColumn(name = "subject_principal_id")
    lateinit var subjectPrincipal: Principal

    @ManyToOne(
        optional = false,
        fetch = FetchType.LAZY
    )
    @JoinColumn(name = "scope_id")
    lateinit var scope: Scope

    @ManyToOne(
        optional = false,
        fetch = FetchType.LAZY
    )
    @JoinColumn(name = "role_id")
    lateinit var role: Role
}
