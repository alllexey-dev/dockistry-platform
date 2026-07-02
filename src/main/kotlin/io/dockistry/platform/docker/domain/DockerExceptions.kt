package io.dockistry.platform.docker.domain

import io.dockistry.platform.shared.exception.BadRequestException
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
