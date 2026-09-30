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
}
