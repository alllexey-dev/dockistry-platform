package io.dockistry.platform.iam.infrastructure

import io.dockistry.platform.iam.domain.Permission
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PermissionRepository : JpaRepository<Permission, UUID> {
    fun findByKey(key: String): Permission?
}
