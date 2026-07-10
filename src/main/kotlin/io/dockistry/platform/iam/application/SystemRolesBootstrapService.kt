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
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class SystemRolesBootstrapService(
    private val permissionRepository: PermissionRepository,
    private val roleRepository: RoleRepository,
    private val scopeRepository: ScopeRepository
) : ApplicationRunner {

    @Transactional
    override fun run(args: ApplicationArguments) {
        ensureSystemRolesAndPermissions()
        ensureGlobalScope()
    }

    private fun ensureSystemRolesAndPermissions() {
        val projectCreatePermission = permission(
            key = SystemPermissionNames.PROJECT_CREATE,
            description = "Allows creating projects or project contents depending on the scope where this permission is granted."
        )
        val projectReadPermission = permission(
            key = SystemPermissionNames.PROJECT_READ,
            description = "Allows viewing project metadata and project-level resources in the granted scope."
        )
        val projectUpdatePermission = permission(
            key = SystemPermissionNames.PROJECT_UPDATE,
            description = "Allows changing project settings, metadata, and operational configuration in the granted scope."
        )
        val projectDeletePermission = permission(
            key = SystemPermissionNames.PROJECT_DELETE,
            description = "Allows deleting projects and their contents in the granted scope."
        )
        val projectArchivePermission = permission(
            key = SystemPermissionNames.PROJECT_ARCHIVE,
            description = "Allows archiving project(s) depending on the granted scope."
        )
        val projectMembersManagePermission = permission(
            key = SystemPermissionNames.PROJECT_MEMBERS_MANAGE,
            description = "Allows adding and removing project member role bindings in the granted scope."
        )
        val projectContainersManagePermission = permission(
            key = SystemPermissionNames.PROJECT_CONTAINER_MANAGE,
            description = "Allows managing lifecycle actions for project containers in the granted scope."
        )
        val projectServicesManagePermission = permission(
            key = SystemPermissionNames.PROJECT_SERVICES_MANAGE,
            description = "Allows creating, updating, and deleting managed services in the granted project scope."
        )
        val dockerImagesReadPermission = permission(
            key = SystemPermissionNames.DOCKER_IMAGES_READ,
            description = "Allows viewing Docker image metadata through the Docker agent in the granted scope."
        )
        val dockerImagesManagePermission = permission(
            key = SystemPermissionNames.DOCKER_IMAGES_MANAGE,
            description = "Allows pulling and removing Docker images through the Docker agent in the granted scope."
        )

        val projectAdmin = role(
            name = SystemRoleNames.PROJECT_ADMIN,
            description = "System role for full project management, including creation, reading, updates, and deletion or archival. Effective access is limited by the scope used in the role binding."
        )
        val projectOperator = role(
            name = SystemRoleNames.PROJECT_OPERATOR,
            description = "System role for day-to-day project operation: viewing project data and changing project configuration. Effective access is limited by the scope used in the role binding."
        )
        val projectViewer = role(
            name = SystemRoleNames.PROJECT_VIEWER,
            description = "System role for read-only access to project data. Effective access is limited by the scope used in the role binding."
        )

        val adminPermissions = setOf(
            projectCreatePermission,
            projectReadPermission,
            projectUpdatePermission,
            projectDeletePermission,
            projectArchivePermission,
            projectMembersManagePermission,
            projectContainersManagePermission,
            projectServicesManagePermission,
            dockerImagesReadPermission,
            dockerImagesManagePermission
        )
        val operatorPermissions = setOf(
            projectReadPermission,
            projectUpdatePermission,
            projectContainersManagePermission,
            projectServicesManagePermission
        )
        val viewerPermissions = setOf(projectReadPermission)

        if (projectAdmin.permissions != adminPermissions) {
            projectAdmin.permissions.clear()
            projectAdmin.permissions.addAll(adminPermissions)
        }

        if (projectOperator.permissions != operatorPermissions) {
            projectOperator.permissions.clear()
            projectOperator.permissions.addAll(operatorPermissions)
        }

        if (projectViewer.permissions != viewerPermissions) {
            projectViewer.permissions.clear()
            projectViewer.permissions.addAll(viewerPermissions)
        }
    }

    fun ensureGlobalScope() {
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
