package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import java.util.UUID

interface ProjectSecretDelegationRepository : JpaRepository<ProjectSecretDelegation, UUID> {
    @Query("""
        SELECT EXISTS (
            SELECT 1
            FROM ProjectSecretDelegation delegation
            WHERE delegation.project.id = :projectId
              AND delegation.secret.ownerPrincipal.id = :ownerPrincipalId
        )
    """)
    fun existsByProjectAndOwnerPrincipalId(
        projectId: UUID,
        ownerPrincipalId: UUID
    ): Boolean

    @Query("""
        SELECT DISTINCT delegation.secret.ownerPrincipal.id
        FROM ProjectSecretDelegation delegation
        WHERE delegation.project.id = :projectId
          AND delegation.secret.ownerPrincipal.id IN (:ownerPrincipalIds)
    """)
    fun findDelegatedOwnerIdsByProjectAndOwnerPrincipalIds(
        projectId: UUID,
        ownerPrincipalIds: Collection<UUID>
    ): List<UUID>

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

    @Query("""
        SELECT delegation
        FROM ProjectSecretDelegation delegation
        JOIN FETCH delegation.secret secret
        WHERE delegation.project.id = :projectId
        ORDER BY secret.name, secret.id
    """)
    fun findByProjectIdWithSecret(projectId: UUID): List<ProjectSecretDelegation>

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

    @Query("""
        SELECT delegation.*
        FROM project_secret_delegations delegation
        WHERE delegation.secret_id = :secretId
    """, nativeQuery = true)
    fun findBySecretId(
        secretId: String
    ): List<ProjectSecretDelegation>

    @Query("""
        SELECT delegation
        FROM ProjectSecretDelegation delegation
        JOIN FETCH delegation.project project
        WHERE delegation.secret.id = :secretId
        ORDER BY project.name, project.id
    """)
    fun findBySecretIdWithProject(secretId: String): List<ProjectSecretDelegation>

    /* Locks every project delegation of one Secret in a deterministic order. */
    @Query(
        """
        SELECT delegation.*
        FROM project_secret_delegations delegation
        WHERE delegation.secret_id = :secretId
        ORDER BY delegation.project_id
        FOR NO KEY UPDATE
    """, nativeQuery = true
    )
    fun findBySecretIdForNoKeyUpdate(
        secretId: String
    ): List<ProjectSecretDelegation>

    @Query("""
        DELETE FROM ProjectSecretDelegation delegation
        WHERE delegation.project.id = :projectId
          AND delegation.secret.id = :secretId
    """)
    @Modifying
    fun deleteByProjectAndSecret(
        projectId: UUID,
        secretId: String
    )

    @Query("""
        DELETE FROM ProjectSecretDelegation delegation
        WHERE delegation.secret.id = :secretId
    """)
    @Modifying
    fun deleteBySecretId(secretId: String): Int
}
