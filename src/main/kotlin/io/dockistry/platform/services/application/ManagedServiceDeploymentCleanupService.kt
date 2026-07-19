package io.dockistry.platform.services.application

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.DockerVolumeNotFoundException
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ManagedServiceDeploymentCleanupService(
    private val dockerAgentClient: DockerAgentClient,
    private val deploymentLifecycleService: ManagedServiceDeploymentLifecycleService
) {
    fun cleanup(
        projectId: UUID,
        serviceId: UUID,
        deploymentId: UUID
    ) {
        val filter = DockerLabelsFilter(
            labels = DockerLabels.forDeployment(
                projectId = projectId,
                serviceId = serviceId,
                deploymentId = deploymentId
            )
        )

        dockerAgentClient.listContainers(filter).forEach { container ->
            try {
                dockerAgentClient.deleteContainer(container.id)
            } catch (_: DockerContainerNotFoundException) {
                // A concurrent cleanup already removed this container.
            }
        }

        dockerAgentClient.listVolumes(filter).forEach { volume ->
            try {
                dockerAgentClient.removeVolume(volume.name)
            } catch (_: DockerVolumeNotFoundException) {
                // A concurrent cleanup already removed this volume.
            }
        }

        deploymentLifecycleService.completeCancellation(deploymentId)
    }
}
