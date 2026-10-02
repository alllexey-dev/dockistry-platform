package io.dockistry.platform.iam.infrastructure

import io.dockistry.platform.iam.domain.Principal
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface PrincipalRepository : JpaRepository<Principal, UUID> {
    fun findPrincipalById(id: UUID): Principal?

    @Query("""
        SELECT member.id
        FROM Group g
        JOIN g.members member
        WHERE g.id = :groupId
    """)
    fun findGroupMemberIds(groupId: UUID): List<UUID>
}
