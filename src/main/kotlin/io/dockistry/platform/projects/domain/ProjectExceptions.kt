package io.dockistry.platform.projects.domain

import io.dockistry.platform.shared.exception.BadRequestException
import io.dockistry.platform.shared.exception.ConflictException
import io.dockistry.platform.shared.exception.InternalServerErrorException
import io.dockistry.platform.shared.exception.NotFoundException
import io.dockistry.platform.shared.exception.ServiceUnavailableException

class InvalidProjectNameException(
    message: String
) : BadRequestException(message, "invalid_project_name")

class InvalidProjectDescriptionException(
    message: String
) : BadRequestException(message, "invalid_project_description")

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

class ProjectOperationInProgressException :
    ConflictException(
        message = "Another project-related Docker operation is currently in progress",
        code = "project_operation_in_progress"
    )

class ProjectOperationConcurrencyCheckFailureException :
    InternalServerErrorException(
        message = "Couldn't make sure there are no other project-related " +
                "Docker operations running concurrently",
        code = "project_operation_concurrency_check_failure"
    )

class ProjectExecutionCapacityExhaustedException :
    ServiceUnavailableException(
        message = "Project execution capacity is temporarily exhausted, try again later",
        code = "project_execution_capacity_exhausted"
    )

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
