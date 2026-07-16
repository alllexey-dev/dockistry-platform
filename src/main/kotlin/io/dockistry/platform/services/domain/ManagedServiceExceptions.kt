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
