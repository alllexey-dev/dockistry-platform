package io.dockistry.platform.projects.application

import io.dockistry.platform.iam.application.PermissionCheckService
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.domain.SystemPermissionNames
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProjectAccessService(
    private val permissionCheckService: PermissionCheckService
) {
    fun canCreate(principal: Principal): Boolean {
        return permissionCheckService.hasGlobalPermission(SystemPermissionNames.PROJECT_CREATE, principal.id)
    }

    fun canRead(scope: Scope, principal: Principal): Boolean {
        return permissionCheckService.hasPermission(SystemPermissionNames.PROJECT_READ, scope.id, principal.id)
    }

    fun canUpdate(scope: Scope, principal: Principal): Boolean {
        return canRead(scope, principal) &&
                permissionCheckService.hasPermission(SystemPermissionNames.PROJECT_UPDATE, scope.id, principal.id)
    }

    fun canArchive(scope: Scope, principal: Principal): Boolean {
        return canRead(scope, principal) &&
                permissionCheckService.hasPermission(SystemPermissionNames.PROJECT_ARCHIVE, scope.id, principal.id)
    }

    fun canDelete(scope: Scope, principal: Principal): Boolean {
        return canRead(scope, principal) &&
                permissionCheckService.hasPermission(SystemPermissionNames.PROJECT_DELETE, scope.id, principal.id)
    }
}
