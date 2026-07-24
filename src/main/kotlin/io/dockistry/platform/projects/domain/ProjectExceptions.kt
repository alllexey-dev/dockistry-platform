package io.dockistry.platform.projects.domain

import io.dockistry.platform.shared.exception.BadRequestException
import io.dockistry.platform.shared.exception.ConflictException
import io.dockistry.platform.shared.exception.NotFoundException

class InvalidProjectNameException(
    message: String
) : BadRequestException(message, "invalid_project_name")

class ProjectNotFoundException(
    message: String
) : NotFoundException(message, "project_not_found")

class InvalidProjectSlugException(
    message: String
) : BadRequestException(message, "invalid_project_slug")

class ProjectAlreadyExistsException(
    message: String
) : ConflictException(message, "project_already_exists")

class ProjectVersionConflictException(
    message: String
) : ConflictException(message, "project_version_conflict")

class ProjectRoleNotAssignableException(
    message: String
) : BadRequestException(message, "project_role_not_assignable")

class ProjectRoleAlreadyAssignedException(
    message: String
) : ConflictException(message, "project_role_already_assigned")

class ProjectRoleBindingNotFoundException(
    message: String
) : NotFoundException(message, "project_role_binding_not_found")

class ProjectMemberNotFoundException(
    message: String
) : NotFoundException(message, "project_member_not_found")

class ProjectMemberPrincipalNotFoundException(
    message: String
) : NotFoundException(message, "project_member_principal_not_found")

class ProjectDeletionBlockedByManagedServicesException(
    message: String
) : ConflictException(
    message = message,
    code = "project_deletion_blocked_by_managed_services"
)
