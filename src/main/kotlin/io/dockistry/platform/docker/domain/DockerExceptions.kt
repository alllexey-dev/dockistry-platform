package io.dockistry.platform.docker.domain

import io.dockistry.platform.shared.exception.BadRequestException
import io.dockistry.platform.shared.exception.ConflictException
import io.dockistry.platform.shared.exception.ForbiddenException
import io.dockistry.platform.shared.exception.InternalServerErrorException
import io.dockistry.platform.shared.exception.NotFoundException
import io.dockistry.platform.shared.exception.ServiceUnavailableException

class InvalidContainerLogsRequestException(
    message: String
) : BadRequestException(message, "invalid_container_logs_request")

class DockerAgentUnavailableException(
    message: String
) : ServiceUnavailableException(message, "docker_agent_unavailable")

class DockerOperationFailedException(
    message: String = "Docker operation failed"
) : InternalServerErrorException(message, "docker_operation_failed")

class DockerContainerNotFoundException(
    message: String
) : NotFoundException(message, "docker_container_not_found")

class DockistryManagedContainerAccessForbiddenException(
    message: String =
        "Dockistry-managed containers are not accessible through the generic container API"
) : ForbiddenException(message, "dockistry_managed_container_access_forbidden")

class DockerImageNotFoundException(
    message: String
) : NotFoundException(message, "docker_image_not_found")

class DockerImagePullTimeoutException(
    message: String = "Docker image pull timed out"
) : ServiceUnavailableException(message, "docker_image_pull_timeout")

class DockerImagePullFailedException(
    message: String
) : ServiceUnavailableException(message, "docker_image_pull_failed")

class DockerImagePullAccessDeniedException(
    message: String
) : ForbiddenException(message, "docker_image_pull_access_denied")

class DockerImageRegistryUnavailableException(
    message: String
) : ServiceUnavailableException(message, "docker_image_registry_unavailable")

class DockerImageRemoveConflictException(
    message: String = "Docker image remove conflict"
) : ConflictException(message, "docker_image_rm_conflict")

class DockerContainerAlreadyStartedException(
    message: String = "Docker container is already started"
) : ConflictException(message, "docker_container_already_started")

class DockerContainerAlreadyStoppedException(
    message: String = "Docker container is already stopped"
) : ConflictException(message, "docker_container_already_stopped")

class DockerContainerAlreadyPausedException(
    message: String = "Docker container is already paused"
) : ConflictException(message, "docker_container_already_paused")

class DockerContainerNotPausedException(
    message: String = "Docker container is not paused"
) : ConflictException(message, "docker_container_not_paused")

class DockerContainerNotRunningException(
    message: String = "Docker container is not running"
) : ConflictException(message, "docker_container_not_running")

class DockerContainerLifecycleConflictException(
    message: String
) : ConflictException(message, "docker_container_lifecycle_conflict")

class DockerContainerCreateDependencyNotFoundException(
    message: String
) : NotFoundException(message, "docker_container_create_dependency_not_found")

class DockerContainerCreateNameConflictException(
    message: String
) : ConflictException(message, "docker_container_create_name_conflict")

class DockerContainerCreateRejectedException(
    message: String
) : BadRequestException(message, "docker_container_create_rejected")

class DockerContainerRuntimeResourceConflictException(
    message: String
) : ConflictException(message, "docker_container_runtime_resource_conflict")

class DockerContainerRemoveConflictException(
    message: String
) : ConflictException(message, "docker_container_remove_conflict")

class DockerNetworkNotFoundException(
    message: String
) : NotFoundException(message, "docker_network_not_found")

class DockerNetworkCreateNameConflictException(
    message: String
) : ConflictException(message, "docker_network_create_name_conflict")

class DockerNetworkCreateRejectedException(
    message: String
) : BadRequestException(message, "docker_network_create_rejected")

class DockerNetworkCreateDependencyNotFoundException(
    message: String
) : NotFoundException(message, "docker_network_create_dependency_not_found")

class DockerNetworkRemoveConflictException(
    message: String
) : ConflictException(message, "docker_network_remove_conflict")

class DockerNetworkNameAmbiguityException(
    name: String
) : ConflictException(
    "Multiple Docker networks use name '$name'; contact an administrator",
    "docker_network_name_ambiguity"
)

class DockerNetworkOwnershipConflictException(
    message: String = "Docker network ownership mismatch; contact an administrator"
) : ConflictException(message, "docker_network_ownership_conflict")

class DockerNetworkCreateVerificationFailedException(
    name: String
) : InternalServerErrorException(
    "Docker network '$name' couldn't be verified after creation; contact an administrator",
    "docker_network_create_verification_failed"
)

class DockerVolumeNotFoundException(
    message: String
) : NotFoundException(message, "docker_volume_not_found")

class DockerVolumeCreateNameConflictException(
    message: String
) : ConflictException(message, "docker_volume_create_name_conflict")

class DockerVolumeCreateRejectedException(
    message: String
) : BadRequestException(message, "docker_volume_create_rejected")

class DockerVolumeCreateDependencyNotFoundException(
    message: String
) : NotFoundException(message, "docker_volume_create_dependency_not_found")

class DockerVolumeRemoveConflictException(
    message: String
) : ConflictException(message, "docker_volume_remove_conflict")

class DockerVolumeOwnershipConflictException(
    message: String = "Docker volume ownership mismatch; contact an administrator"
) : ConflictException(message, "docker_volume_ownership_conflict")

class DockerVolumeCreateVerificationFailedException(
    name: String
) : InternalServerErrorException(
    "Docker volume '$name' couldn't be verified after creation; contact an administrator",
    "docker_volume_create_verification_failed"
)
