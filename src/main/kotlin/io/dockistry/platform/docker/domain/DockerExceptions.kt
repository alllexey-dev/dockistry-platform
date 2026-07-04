package io.dockistry.platform.docker.domain

import io.dockistry.platform.shared.exception.BadRequestException
import io.dockistry.platform.shared.exception.ConflictException
import io.dockistry.platform.shared.exception.NotFoundException
import io.dockistry.platform.shared.exception.ServiceUnavailableException

class InvalidContainerLogsRequestException(
    message: String
) : BadRequestException(message, "invalid_container_logs_request")

class DockerAgentUnavailableException(
    message: String
) : ServiceUnavailableException(message, "docker_agent_unavailable")

class DockerContainerNotFoundException(
    message: String
) : NotFoundException(message, "docker_container_not_found")

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
