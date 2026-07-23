package io.dockistry.platform.services.domain

import io.dockistry.platform.shared.exception.BadRequestException
import io.dockistry.platform.shared.exception.ConflictException
import io.dockistry.platform.shared.exception.NotFoundException
import java.util.UUID

class ManagedServiceNotFoundException(
    message: String
) : NotFoundException(message, "managed_service_not_found")

class ManagedServiceAlreadyExistsException(
    projectId: UUID,
    serviceName: String
) : ConflictException(
    "Managed service '$serviceName' already exists in project $projectId",
    "managed_service_already_exists"
)

class ManagedServiceVersionConflictException(
    message: String
) : ConflictException(message, "managed_service_version_conflict")

class ManagedServiceOperationInProgressException(
    message: String = "Another managed service operation is currently in progress"
) : ConflictException(message, "managed_service_operation_in_progress")

class ManagedServiceDeletionInProgressException(
    message: String = "Managed service deletion is currently in progress"
) : ConflictException(message, "managed_service_deletion_in_progress")

class InvalidManagedServiceNameException(
    message: String
) : BadRequestException(message, "invalid_managed_service_name")

class InvalidManagedServiceImageRefException(
    message: String
) : BadRequestException(message, "invalid_managed_service_image_ref")

class InvalidManagedServiceConfigException(
    message: String
) : BadRequestException(message, "invalid_managed_service_config")

class DeployAlreadyInProcessException(
    message: String
) : ConflictException(message, "deploy_already_in_process")

class ContainerNameConflictDeletionRequiredException() : ConflictException(
    message = "Container with same name is already connected to your project, please delete it and try again",
    code = "container_name_conflict_deletion_required"
)

class ContainerNameConflictSupportRequiredException() : ConflictException(
    message = "Container name conflicts with our system, please contact support",
    code = "container_name_conflict_support_required"
)

class ManagedServiceNotDeployedException(
    message: String
) : ConflictException(message = message, code = "managed_service_not_deployed")

class ManagedServiceNotRunningException(
    message: String
) : ConflictException(message = message, code = "managed_service_not_running")

class ManagedServiceDeploymentCanceled(
    message: String
) : ConflictException(message = message, code = "managed_service_deployment_canceled")

class ManagedServiceDeletionBlockedByDeployment(
    message: String
) : ConflictException(message = message, code = "managed_service_deployment_blocked_by_deployment")

class DeploymentNotFoundException(
    message: String
) : NotFoundException(message = message, code = "deployment_not_found")
