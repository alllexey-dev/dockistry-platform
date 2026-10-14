package io.dockistry.platform.iam.infrastructure

import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.domain.Scope
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface RoleBindingRepository : JpaRepository<RoleBinding, UUID> {
    fun findByScopeAndSubjectPrincipalId(
        scope: Scope,
        subjectPrincipalId: UUID
    ): List<RoleBinding>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM RoleBinding rb
            JOIN Project p ON p.scope.id = rb.scope.id
            WHERE p.id = :projectId
                AND rb.role.id = :roleId
                AND rb.subjectPrincipal.id = :principalId
        )
        """
    )
    fun isRoleAssigned(projectId: UUID, roleId: UUID, principalId: UUID): Boolean

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM RoleBinding rb
            JOIN Project p ON p.scope.id = rb.scope.id
            WHERE p.id = :projectId
                AND rb.subjectPrincipal.id = :principalId
                AND rb.id = :roleBindingId
        )
        """
    )
    fun isAssigned(projectId: UUID, roleBindingId: UUID, principalId: UUID): Boolean

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        DELETE FROM RoleBinding rb
        WHERE rb.id = :roleBindingId
            AND rb.subjectPrincipal.id = :principalId
            AND rb.scope.id IN (
                SELECT p.scope.id
                FROM Project p
                WHERE p.id = :projectId
            )
        """
    )
    fun deleteBindingInProject(
        projectId: UUID,
        principalId: UUID,
        roleBindingId: UUID
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        DELETE FROM RoleBinding rb
        WHERE rb.subjectPrincipal.id = :principalId
            AND rb.id IN (:roleBindingIds)
            AND rb.scope.id IN (
                SELECT p.scope.id
                FROM Project p
                WHERE p.id = :projectId
            )
        """
    )
    fun deleteBindingsInProject(
        projectId: UUID,
        principalId: UUID,
        roleBindingIds: Collection<UUID>
    ): Int
}
