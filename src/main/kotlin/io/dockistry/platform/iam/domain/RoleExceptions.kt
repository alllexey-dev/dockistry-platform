package io.dockistry.platform.iam.domain

import io.dockistry.platform.shared.exception.BadRequestException
import io.dockistry.platform.shared.exception.ConflictException

class RoleNameCollidesSystemRoleException(
    message: String = "Provided role's name collides with existing system role"
) : ConflictException(message, "role_name_collides_system_role")

class RoleNameCollidesProjectRoleException(
    message: String = "Provided role's name collides with existing project role"
) : ConflictException(message, "role_name_collides_project_role")

class InvalidRoleOwnershipException(
    message: String = "Role must be either system-wide or attached to a project, but not both"
) : BadRequestException(message, "invalid_role_ownership_exception")
