package io.dockistry.platform.iam.infrastructure

import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.domain.ScopeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ScopeQueryRepository : JpaRepository<RoleBinding, UUID> {
    @Query(
        """
        SELECT DISTINCT s.id
              FROM RoleBinding rb
              JOIN rb.scope s
              JOIN rb.role.permissions pm
              WHERE (rb.subjectPrincipal.id = :principalId OR
                     rb.subjectPrincipal.id IN (SELECT g.id
                                                FROM Group g
                                                         JOIN g.members m
                                                WHERE :principalId = m.id)
                  )
                  AND s.scopeType = :scopeType
                  AND pm.key = :permissionKey
    """
    )
    fun effectiveScopeIds(
        @Param("principalId")
        principalId: UUID,

        @Param("permissionKey")
        permissionKey: String,

        @Param("scopeType")
        scopeType: ScopeType
    ): MutableSet<UUID>
}
