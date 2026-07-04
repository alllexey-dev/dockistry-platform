package io.dockistry.platform.projects.application

import io.dockistry.platform.iam.application.PermissionCheckService
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.iam.domain.SystemPermissionNames
import org.springframework.stereotype.Service

@Service
class ProjectAccessService(
    private val permissionCheckService: PermissionCheckService
) {
    fun hasCreatePermission(principal: Principal): Boolean {
        return permissionCheckService.hasGlobalPermission(
            SystemPermissionNames.PROJECT_CREATE,
            principal.id
        )
    }

    fun hasReadPermission(scope: Scope, principal: Principal): Boolean {
        return permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_READ,
            principal.id,
            scope.id
        )
    }

    fun hasUpdatePermission(scope: Scope, principal: Principal): Boolean {
        return permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_UPDATE,
            principal.id,
            scope.id
        )
    }

    fun hasArchivePermission(scope: Scope, principal: Principal): Boolean {
        return permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_ARCHIVE,
            principal.id,
            scope.id
        )
    }

    fun hasDeletePermission(scope: Scope, principal: Principal): Boolean {
        return permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_DELETE,
            principal.id,
            scope.id
        )
    }

    fun hasMemberManagementPermission(scope: Scope, principal: Principal): Boolean {
        return permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_MEMBERS_MANAGE,
            principal.id,
            scope.id
        )
    }

    fun hasProjectContainersManagePermission(scope: Scope, principal: Principal): Boolean {
        return permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_CONTAINER_MANAGE,
            principal.id,
            scope.id
        )
    }
}
