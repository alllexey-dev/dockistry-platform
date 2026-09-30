package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProjectSecretDelegationRepository : JpaRepository<ProjectSecretDelegation, UUID> {
    @Query("""
        SELECT delegation
        FROM ProjectSecretDelegation delegation
        WHERE delegation.project.id = :projectId
          AND delegation.secret.id = :secretId
    """)
    fun getByProjectAndSecret(
        projectId: UUID,
        secretId: String
    ): ProjectSecretDelegation?

    @Query(
        """
        SELECT delegation.*
        FROM project_secret_delegations delegation
        WHERE delegation.project_id = :projectId
          AND delegation.secret_id IN (:secretIds)
        ORDER BY delegation.secret_id
        FOR NO KEY UPDATE
    """, nativeQuery = true
    )
    fun findByProjectAndSecretForNoKeyUpdateBatch(
        projectId: UUID,
        secretIds: List<String>
    ): List<ProjectSecretDelegation>

}
