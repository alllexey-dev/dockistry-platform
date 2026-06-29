package io.dockistry.platform.docker.domain

import io.dockistry.platform.shared.exception.NotFoundException
import io.dockistry.platform.shared.exception.ServiceUnavailableException

class DockerAgentUnavailableException(
    message: String
) : ServiceUnavailableException(message, "docker_agent_unavailable")

class DockerContainerNotFoundException(
    message: String
) : NotFoundException(message, "docker_container_not_found")
