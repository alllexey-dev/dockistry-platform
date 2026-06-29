package io.dockistry.platform.docker.application

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.dto.ContainerDetails
import io.dockistry.platform.docker.domain.dto.ContainerFilter
import io.dockistry.platform.docker.domain.dto.ContainerStats
import io.dockistry.platform.docker.domain.dto.ContainerSummary
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.projects.domain.Project
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProjectContainerService(
    private val dockerAgentClient: DockerAgentClient,
    private val projectAccessGuard: ProjectAccessGuard
) {
    @Transactional(readOnly = true)
    fun listContainers(projectId: UUID, principal: Principal): List<ContainerSummary> {
        val project = projectAccessGuard.requireReadable(projectId, principal)
        return dockerAgentClient.listContainers(project.toContainerFilter())
    }

    @Transactional(readOnly = true)
    fun inspectContainer(
        projectId: UUID,
        containerId: String,
        principal: Principal
    ): ContainerDetails {
        val project = projectAccessGuard.requireReadable(projectId, principal)
        val container = dockerAgentClient.inspectContainer(containerId)
        return container.takeIf { it.belongsTo(project) } ?: throw notFound()
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

        return dockerAgentClient.getContainerStats(containerId)
    }

    private fun Project.toContainerFilter(): ContainerFilter {
        return ContainerFilter(
            labels = mapOf(
                DockerLabels.MANAGED to "true",
                DockerLabels.PROJECT_ID to id.toString()
            )
        )
    }

    private fun ContainerDetails.belongsTo(project: Project): Boolean {
        return labels[DockerLabels.MANAGED] == "true" &&
            labels[DockerLabels.PROJECT_ID] == project.id.toString()
    }

    private fun notFound(): DockerContainerNotFoundException {
        return DockerContainerNotFoundException("Docker container couldn't be found")
    }
}
