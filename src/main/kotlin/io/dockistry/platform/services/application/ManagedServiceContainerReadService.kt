package io.dockistry.platform.services.application

import io.dockistry.platform.docker.application.ContainerReadService
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.dto.ContainerDetails
import io.dockistry.platform.docker.domain.dto.ContainerLogs
import io.dockistry.platform.docker.domain.dto.ContainerStats
import io.dockistry.platform.docker.domain.model.DockerContainerLogsRequest
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.services.domain.ManagedServiceNotDeployedException
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ManagedServiceContainerReadService(
    private val containerReadService: ContainerReadService
) {
    fun inspectContainer(projectId: UUID, serviceId: UUID): ContainerDetails {
        return containerReadService.inspectContainer(
            requireContainerId(projectId, serviceId)
        )
    }

    fun getContainerStats(projectId: UUID, serviceId: UUID): ContainerStats {
        return containerReadService.getContainerStats(
            requireContainerId(projectId, serviceId)
        )
    }

    fun getLogs(
        projectId: UUID,
        serviceId: UUID,
        request: DockerContainerLogsRequest
    ): ContainerLogs {
        return containerReadService.getLogs(
            containerId = requireContainerId(projectId, serviceId),
            request = request
        )
    }

    private fun requireContainerId(projectId: UUID, serviceId: UUID): String {
        val containers = containerReadService.listContainers(
            filter = DockerLabelsFilter(
                labels = DockerLabels.forService(
                    projectId,
                    serviceId
                )
            )
        )

        if (containers.size > 1) {
            throw InternalDataIntegrityException(
                "Managed service $serviceId has more than one container"
            )
        }

        return containers.singleOrNull()?.id
            ?: throw ManagedServiceNotDeployedException(
                "Managed service $serviceId is not deployed"
            )
    }
}
