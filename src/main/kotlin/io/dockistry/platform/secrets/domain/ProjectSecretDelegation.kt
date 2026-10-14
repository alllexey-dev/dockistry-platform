package io.dockistry.platform.secrets.domain

import io.dockistry.platform.projects.domain.Project
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
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "project_secret_delegations",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uc_project_secret_delegations_secret_project",
            columnNames = ["secret_id", "project_id"]
        )
    ]
)
class ProjectSecretDelegation(
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "secret_id", nullable = false)
    val secret: Secret,

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    val project: Project,

    createdAt: Instant,
    updatedAt: Instant
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = createdAt

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = updatedAt
        protected set
}
