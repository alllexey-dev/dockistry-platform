package io.dockistry.platform.services.application

import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.infrastructure.LocalDockerAgentClient
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProjectNetworkService(
    private val dockerAgentClient: LocalDockerAgentClient
) {
    fun createNetwork(projectId: UUID, projectSlug: String): String {
        return dockerAgentClient.createNetwork(
            name = projectNetworkName(projectSlug),
            labels = mapOf(
                DockerLabels.MANAGED to "true",
                DockerLabels.PROJECT_ID to projectId.toString()
            )
        ).name
    }

    fun findNetwork(projectSlug: String): String? {
        return dockerAgentClient.findNetworkByName(
            projectNetworkName(projectSlug)
        )?.name
    }

    fun projectNetworkName(projectSlug: String) = "dockistry_$projectSlug"
}
