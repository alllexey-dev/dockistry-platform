package io.dockistry.platform.iam.infrastructure

import io.dockistry.platform.iam.domain.RoleBinding
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.Optional
import java.util.UUID

interface PermissionQueryRepository : JpaRepository<RoleBinding, UUID> {
    @Query(
        """
            SELECT EXISTS(SELECT 1
              FROM RoleBinding rb
                       JOIN Scope s ON s.id = rb.scope.id
                       JOIN rb.role.permissions pm
              WHERE (rb.subjectPrincipal.id = :principalId OR
                     rb.subjectPrincipal.id IN (SELECT g.id
                                                FROM Group g
                                                         JOIN g.members m
                                                WHERE :principalId = m.id)
                  )
                AND (s.id = :scopeId OR s.scopeType = ScopeType.GLOBAL)
                AND pm.key = :permissionKey)
            FROM Principal p
            WHERE p.id = :principalId
    """
    )
    fun existsEffectivePermission(principalId: UUID, permissionKey: String, scopeId: UUID? = null): Optional<Boolean>
}
