package io.dockistry.platform.projects.domain

import io.dockistry.platform.shared.exception.BadRequestException
import io.dockistry.platform.shared.exception.ConflictException
import io.dockistry.platform.shared.exception.ForbiddenException
import io.dockistry.platform.shared.exception.NotFoundException

class InvalidProjectNameException(
    message: String
) : BadRequestException(message, "invalid_project_name")

class InsufficientPermissionException(
    message: String
) : ForbiddenException(message, "insufficient_permission_for_operation")

class ProjectNotFoundException(
    message: String
) : NotFoundException(message, "project_not_found")

class InvalidProjectSlugException(
    message: String
) : BadRequestException(message, "invalid_project_slug")

class ProjectAlreadyExistsException(
    message: String
) : ConflictException(message, "project_already_exists")
