package io.dockistry.platform.iam.infrastructure

import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.iam.domain.ScopeType
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ScopeRepository : JpaRepository<Scope, UUID> {
    fun findByScopeType(scopeType: ScopeType): List<Scope>
}