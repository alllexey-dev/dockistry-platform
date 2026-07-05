package io.dockistry.platform.docker.application

import io.dockistry.platform.docker.domain.dto.DockerAgentStatus
import org.springframework.stereotype.Service

@Service
class DockerStatusService(
    private val containerService: ContainerService,
    private val dockerApiDtoMapper: DockerApiDtoMapper
) {
    fun getStatus(): DockerAgentStatus {
        return dockerApiDtoMapper.toStatusDto(containerService.status())
    }
}
