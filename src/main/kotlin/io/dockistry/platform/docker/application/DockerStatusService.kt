package io.dockistry.platform.docker.application

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.dto.DockerAgentStatus
import org.springframework.stereotype.Service

@Service
class DockerStatusService(
    private val dockerAgentClient: DockerAgentClient,
    private val dockerApiDtoMapper: DockerApiDtoMapper
) {
    fun getStatus(): DockerAgentStatus {
        return dockerApiDtoMapper.toStatusDto(dockerAgentClient.status())
    }
}
