package io.dockistry.platform.docker.application

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.model.DockerContainerDetails
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.projects.domain.Project
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProjectContainerAccessGuard(
    private val dockerAgentClient: DockerAgentClient,
    private val projectAccessGuard: ProjectAccessGuard
) {
    fun requireReadableContainer(
        projectId: UUID,
        containerId: String,
        principal: Principal
    ): ProjectContainerAccess {
        val project = projectAccessGuard.requireReadable(projectId, principal)
        val container = dockerAgentClient.inspectContainer(containerId)

        if (!container.belongsTo(project)) {
            throw notFound()
        }

        return ProjectContainerAccess(project, container)
    }

    fun requireManageableContainer(
        projectId: UUID,
        containerId: String,
        principal: Principal
    ): ProjectContainerAccess {
        val access = requireReadableContainer(projectId, containerId, principal)
        projectAccessGuard.requireContainersManageable(access.project, principal)
        return access
    }

    private fun DockerContainerDetails.belongsTo(project: Project): Boolean {
        return labels[DockerLabels.MANAGED] == "true" &&
            labels[DockerLabels.PROJECT_ID] == project.id.toString()
    }

    private fun notFound(): DockerContainerNotFoundException {
        return DockerContainerNotFoundException("Docker container couldn't be found")
    }
}
