package io.dockistry.platform.services.application

import io.dockistry.platform.docker.application.ContainerService
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.DockerVolumeNotFoundException
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ManagedServiceDeploymentCleanupService(
    private val containerService: ContainerService,
    private val deploymentLifecycleService: ManagedServiceDeploymentLifecycleService,
    private val deploymentOperationGate: ManagedServiceDeploymentOperationGate
) {
    fun cleanup(
        projectId: UUID,
        serviceId: UUID,
        deploymentId: UUID
    ) {
        deploymentOperationGate.withLock(deploymentId) {
            if (!deploymentLifecycleService.isCancellationRequested(deploymentId)) {
                return@withLock
            }

            try {
                val filter = DockerLabelsFilter(
                    labels = DockerLabels.forDeployment(
                        projectId = projectId,
                        serviceId = serviceId,
                        deploymentId = deploymentId
                    )
                )

                containerService.listContainers(filter).forEach { container ->
                    try {
                        containerService.deleteContainer(container.id)
                    } catch (_: DockerContainerNotFoundException) {
                        // A concurrent cleanup already removed this container.
                    }
                }

                containerService.listVolumes(filter).forEach { volume ->
                    try {
                        containerService.removeVolume(volume.name)
                    } catch (_: DockerVolumeNotFoundException) {
                        // A concurrent cleanup already removed this volume.
                    }
                }

                deploymentLifecycleService.completeCancellation(deploymentId)
            } catch (exception: RuntimeException) {
                recordCleanupFailure(deploymentId, exception)
            }
        }
    }

    private fun recordCleanupFailure(
        deploymentId: UUID,
        cleanupFailure: RuntimeException
    ): Nothing {
        try {
            deploymentLifecycleService.failCleanup(
                deploymentId = deploymentId,
                errorMessage = cleanupFailure.message
            )
        } catch (recordingFailure: RuntimeException) {
            recordingFailure.addSuppressed(cleanupFailure)
            throw recordingFailure
        }

        throw cleanupFailure
    }
}
