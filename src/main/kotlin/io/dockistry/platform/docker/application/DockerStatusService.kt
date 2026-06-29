package io.dockistry.platform.docker.application

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.dto.DockerAgentStatus
import org.springframework.stereotype.Service

@Service
class DockerStatusService(
    private val dockerAgentClient: DockerAgentClient
) {
    fun getStatus(): DockerAgentStatus {
        return dockerAgentClient.status()
    }
}
