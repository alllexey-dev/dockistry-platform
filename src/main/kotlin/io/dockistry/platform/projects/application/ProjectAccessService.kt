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
            SystemPermissionNames.PROJECT_CREATE.key,
            principal.id
        )
    }

    fun hasReadPermission(scope: Scope, principal: Principal): Boolean {
        return permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_READ.key,
            principal.id,
            scope.id
        )
    }

    fun hasUpdatePermission(scope: Scope, principal: Principal): Boolean {
        return permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_UPDATE.key,
            principal.id,
            scope.id
        )
    }

    fun hasArchivePermission(scope: Scope, principal: Principal): Boolean {
        return permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_ARCHIVE.key,
            principal.id,
            scope.id
        )
    }

    fun hasDeletePermission(scope: Scope, principal: Principal): Boolean {
        return permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_DELETE.key,
            principal.id,
            scope.id
        )
    }

    fun hasMemberManagementPermission(scope: Scope, principal: Principal): Boolean {
        return permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_MEMBERS_MANAGE.key,
            principal.id,
            scope.id
        )
    }

    fun hasProjectServicesManagePermission(scope: Scope, principal: Principal): Boolean {
        return permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_SERVICES_MANAGE.key,
            principal.id,
            scope.id
        )
    }

    fun hasSecretsDelegatePermission(scope: Scope, principal: Principal): Boolean {
        return permissionCheckService.hasPermission(
            SystemPermissionNames.SECRETS_DELEGATE.key,
            principal.id,
            scope.id
        )
    }

    fun hasSecretsUsePermission(scope: Scope, principal: Principal): Boolean {
        return permissionCheckService.hasPermission(
            key = SystemPermissionNames.SECRETS_USE.key,
            principalId = principal.id,
            scopeId = scope.id
        )
    }
}
