package io.dockistry.platform.iam.infrastructure

import io.dockistry.platform.iam.domain.Principal
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface PrincipalRepository : JpaRepository<Principal, UUID> {
    fun findPrincipalById(id: UUID): Principal?

    @Query("""
        SELECT principal.id
        FROM Principal principal
        WHERE principal.id IN (:principalIds)
    """)
    fun findExistingIds(principalIds: Collection<UUID>): List<UUID>
}
