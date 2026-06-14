package io.dockistry.platform.iam.infrastructure

import io.dockistry.platform.iam.domain.Principal
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PrincipalRepository : JpaRepository<Principal, UUID> {
    fun findPrincipalById(id: UUID): Principal?
}