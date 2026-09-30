package io.dockistry.platform.iam.application

import io.dockistry.platform.iam.domain.Permission
import io.dockistry.platform.iam.domain.Role
import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.iam.domain.SystemRoleNames
import io.dockistry.platform.iam.infrastructure.PermissionRepository
import io.dockistry.platform.iam.infrastructure.RoleRepository
import io.dockistry.platform.iam.infrastructure.ScopeRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class SystemRolesBootstrapService(
    private val permissionRepository: PermissionRepository,
    private val roleRepository: RoleRepository,
    private val scopeRepository: ScopeRepository
) {

    @Transactional
    fun bootstrap() {
        ensureSystemRolesAndPermissions()
        ensureGlobalScope()
    }

    private fun ensureSystemRolesAndPermissions() {
        SystemPermissionNames.entries.forEach { permissionName ->
            permission(
                key = permissionName.key,
                description = permissionName.description
            )
        }

        SystemRoleNames.entries.forEach { roleName ->
            val role = role(
                name = roleName.key,
                description = roleName.description
            )

            val permissions = roleName.permissionNames.map { name ->
                permission(
                    key = name.key,
                    description = name.description
                )
            }.toSet()

            if (role.permissions.map { it.key }
                    .toSet() != roleName.permissionNames.map { it.key }.toSet()) {
                role.permissions.retainAll(permissions)
                role.permissions.addAll(permissions)
            }
        }
    }

    private fun ensureGlobalScope() {
        if (!scopeRepository.findByScopeType(ScopeType.GLOBAL).isEmpty()) {
            return
        }

        scopeRepository.save(Scope(ScopeType.GLOBAL))
    }

    private fun permission(key: String, description: String): Permission {
        return permissionRepository.findByKey(key)
            ?: permissionRepository.save(Permission(key, description))
    }

    private fun role(name: String, description: String): Role {
        return roleRepository.findByNameAndIsSystemTrue(name)
            ?: roleRepository.save(
                Role(
                    name = name,
                    description = description,
                    isSystem = true
                )
            )
    }
}
