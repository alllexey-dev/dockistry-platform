package io.dockistry.platform.docker.application

import io.dockistry.platform.docker.domain.dto.ContainerDetails
import io.dockistry.platform.docker.domain.dto.ContainerLogs
import io.dockistry.platform.docker.domain.dto.ContainerStats
import io.dockistry.platform.docker.domain.dto.ContainerSummary
import io.dockistry.platform.docker.domain.model.DockerContainerLogsRequest
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import org.springframework.stereotype.Service

@Service
class ContainerReadService(
    private val containerService: ContainerService,
    private val dockerApiDtoMapper: DockerApiDtoMapper
) {
    fun listContainers(
        filter: DockerLabelsFilter = DockerLabelsFilter()
    ): List<ContainerSummary> {
        return dockerApiDtoMapper.toSummaryDtos(
            containerService.listContainers(filter)
        )
    }

    fun inspectContainer(containerId: String): ContainerDetails {
        return dockerApiDtoMapper.toDetailsDto(
            containerService.inspectContainer(containerId)
        )
    }

    fun getContainerStats(containerId: String): ContainerStats {
        return dockerApiDtoMapper.toStatsDto(
            containerService.getContainerStats(containerId)
        )
    }

    fun getLogs(
        containerId: String,
        request: DockerContainerLogsRequest
    ): ContainerLogs {
        return dockerApiDtoMapper.toLogsDto(
            containerService.getLogs(containerId, request)
        )
    }
}
