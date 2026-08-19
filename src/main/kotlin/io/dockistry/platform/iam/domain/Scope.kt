package io.dockistry.platform.iam.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "iam_scopes")
class Scope(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val scopeType: ScopeType
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    @OneToMany(
        mappedBy = "scope"
    )
    val roleBindings: MutableSet<RoleBinding> = mutableSetOf()
}
