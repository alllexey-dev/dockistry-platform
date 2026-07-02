package io.dockistry.platform.docker.application

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.dto.ContainerDetails
import io.dockistry.platform.docker.domain.dto.ContainerLogs
import io.dockistry.platform.docker.domain.dto.ContainerStats
import io.dockistry.platform.docker.domain.dto.ContainerSummary
import io.dockistry.platform.docker.domain.model.DockerContainerDetails
import io.dockistry.platform.docker.domain.model.DockerContainerFilter
import io.dockistry.platform.docker.domain.model.DockerContainerLogsRequest
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.projects.domain.Project
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProjectContainerService(
    private val dockerAgentClient: DockerAgentClient,
    private val projectAccessGuard: ProjectAccessGuard,
    private val dockerApiDtoMapper: DockerApiDtoMapper
) {
    @Transactional(readOnly = true)
    fun listContainers(projectId: UUID, principal: Principal): List<ContainerSummary> {
        val project = projectAccessGuard.requireReadable(projectId, principal)
        return dockerApiDtoMapper.toSummaryDtos(
            dockerAgentClient.listContainers(project.toContainerFilter())
        )
    }

    @Transactional(readOnly = true)
    fun inspectContainer(
        projectId: UUID,
        containerId: String,
        principal: Principal
    ): ContainerDetails {
        val project = projectAccessGuard.requireReadable(projectId, principal)
        val container = dockerAgentClient.inspectContainer(containerId)
        return container
            .takeIf { it.belongsTo(project) }
            ?.let { dockerApiDtoMapper.toDetailsDto(it) }
            ?: throw notFound()
    }

    @Transactional(readOnly = true)
    fun getContainerStats(
        projectId: UUID,
        containerId: String,
        principal: Principal
    ): ContainerStats {
        val project = projectAccessGuard.requireReadable(projectId, principal)
        val container = dockerAgentClient.inspectContainer(containerId)

        if (!container.belongsTo(project)) {
            throw notFound()
        }

        return dockerApiDtoMapper.toStatsDto(dockerAgentClient.getContainerStats(containerId))
    }

    @Transactional(readOnly = true)
    fun getLogs(
        projectId: UUID,
        containerId: String,
        principal: Principal,
        request: DockerContainerLogsRequest
    ): ContainerLogs {
        val project = projectAccessGuard.requireReadable(projectId, principal)
        val container = dockerAgentClient.inspectContainer(containerId)

        if (!container.belongsTo(project)) {
            throw notFound()
        }

        return dockerApiDtoMapper.toLogsDto(dockerAgentClient.getLogs(containerId, request))
    }

    private fun Project.toContainerFilter(): DockerContainerFilter {
        return DockerContainerFilter(
            labels = mapOf(
                DockerLabels.MANAGED to "true",
                DockerLabels.PROJECT_ID to id.toString()
            )
        )
    }

    private fun DockerContainerDetails.belongsTo(project: Project): Boolean {
        return labels[DockerLabels.MANAGED] == "true" &&
            labels[DockerLabels.PROJECT_ID] == project.id.toString()
    }

    private fun notFound(): DockerContainerNotFoundException {
        return DockerContainerNotFoundException("Docker container couldn't be found")
    }
}
