package io.dockistry.platform.iam.infrastructure

import io.dockistry.platform.iam.domain.Group
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface GroupRepository : JpaRepository<Group, UUID> {
    @Query("""
        SELECT member.id
        FROM Group g
        JOIN g.members member
        WHERE g.principal.id = :principalId
    """)
    fun findMemberIdsByPrincipalId(principalId: UUID): List<UUID>
}
