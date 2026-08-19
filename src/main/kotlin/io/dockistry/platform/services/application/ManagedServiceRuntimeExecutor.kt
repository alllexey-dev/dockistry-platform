package io.dockistry.platform.services.application

import io.dockistry.platform.docker.application.ContainerService
import io.dockistry.platform.docker.domain.DockerContainerAlreadyStoppedException
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.services.application.dto.ManagedServiceRuntimeOperationContext
import io.dockistry.platform.services.domain.DeploymentNotFoundException
import io.dockistry.platform.services.domain.ManagedServiceNotDeployedException
import io.dockistry.platform.services.domain.ManagedServiceNotRunningException
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ManagedServiceRuntimeExecutor(
    private val runtimeLifecycleService: ManagedServiceRuntimeLifecycleService,
    private val deploymentLifecycleService: ManagedServiceDeploymentLifecycleService,
    private val containerService: ContainerService,
    private val serviceOperationGate: ManagedServiceOperationGate,
    private val cleanupService: ManagedServiceDeploymentCleanupService
) {
    fun start(serviceId: UUID) {
        executeRuntimeOperation(
            serviceId = serviceId,
            beginTransition = runtimeLifecycleService::beginStart,
            runtimeOperation = { operation ->
                val container = findServiceContainer(
                    projectId = operation.projectId,
                    serviceId = serviceId
                ) ?: throw ManagedServiceNotDeployedException(
                    "Service can't be started because it's not deployed"
                )

                containerService.startContainer(container.id)
            },
            failTransition = runtimeLifecycleService::failStart,
            completeTransition = runtimeLifecycleService::completeStart
        )
    }

    fun stop(serviceId: UUID) {
        val operation = executeRuntimeOperation(
            serviceId = serviceId,
            beginTransition = runtimeLifecycleService::beginStop,
            runtimeOperation = { operation ->
                val stopSucceeded =
                    stopContainer(
                        projectId = operation.projectId,
                        serviceId = serviceId
                    ) || requestDeploymentCancellation(operation)

                if (!stopSucceeded) {
                    throw ManagedServiceNotRunningException(
                        "The service can't be stopped because it is neither deployed nor being deployed"
                    )
                }
            },
            failTransition = runtimeLifecycleService::failStop,
            completeTransition = runtimeLifecycleService::completeStop
        )
        if (operation.deploymentId != null) {
            cleanupService.cleanup(
                projectId = operation.projectId,
                serviceId = operation.serviceId,
                deploymentId = operation.deploymentId
            )
        }
    }

    fun pause(serviceId: UUID) {
        executeRuntimeOperation(
            serviceId = serviceId,
            beginTransition = runtimeLifecycleService::beginPause,
            runtimeOperation = { operation ->
                val container = findServiceContainer(
                    projectId = operation.projectId,
                    serviceId = serviceId
                ) ?: throw ManagedServiceNotDeployedException(
                    "Service can't be paused because it's not deployed"
                )

                containerService.pauseContainer(container.id)
            },
            failTransition = runtimeLifecycleService::failPause,
            completeTransition = runtimeLifecycleService::completePause
        )
    }

    fun unpause(serviceId: UUID) {
        executeRuntimeOperation(
            serviceId = serviceId,
            beginTransition = runtimeLifecycleService::beginUnpause,
            runtimeOperation = { operation ->
                val container = findServiceContainer(
                    projectId = operation.projectId,
                    serviceId = serviceId
                ) ?: throw ManagedServiceNotDeployedException(
                    "Service can't be unpaused because it's not deployed"
                )

                containerService.unpauseContainer(container.id)
            },
            failTransition = runtimeLifecycleService::failUnpause,
            completeTransition = runtimeLifecycleService::completeUnpause
        )
    }

    private fun executeRuntimeOperation(
        serviceId: UUID,
        beginTransition: (UUID) -> ManagedServiceRuntimeOperationContext,
        runtimeOperation: (ManagedServiceRuntimeOperationContext) -> Unit,
        failTransition: (ManagedServiceRuntimeOperationContext) -> Unit,
        completeTransition: (ManagedServiceRuntimeOperationContext) -> Unit
    ): ManagedServiceRuntimeOperationContext {
        return serviceOperationGate.withLock(serviceId) {
            val operation = beginTransition(serviceId)

            try {
                runtimeOperation(operation)
            } catch (exception: RuntimeException) {
                failRuntimeOperation(exception) {
                    failTransition(operation)
                }
            }

            completeTransition(operation)
            operation
        }
    }

    fun removeContainer(projectId: UUID, serviceId: UUID) {
        findServiceContainer(
            projectId = projectId,
            serviceId = serviceId
        )?.let {
            try {
                containerService.deleteContainer(containerId = it.id)
            } catch (_: DockerContainerNotFoundException) {
                // A concurrent Docker-side removal already reached the target state.
            }
        }
    }

    private fun stopContainer(projectId: UUID, serviceId: UUID): Boolean {
        val container = findServiceContainer(
            projectId = projectId,
            serviceId = serviceId
        )
            ?: return false

        val deploymentIsActive = deploymentLifecycleService.hasActiveDeployment(serviceId)
        stopContainer(container.id)

        // Stopping a container is not enough while its deploy executor may still
        // start it again. Returning false forces the short-circuited caller to CAS
        // the active deployment into CANCELING as part of the same stop use case.
        return !deploymentIsActive
    }

    private fun stopContainer(containerId: String): Boolean {
        try {
            containerService.stopContainer(containerId)
        } catch (_: DockerContainerAlreadyStoppedException) {
            // Target service state is already reached
        }
        return true
    }

    private fun findServiceContainer(
        projectId: UUID,
        serviceId: UUID
    ): DockerContainerSummary? {
        return containerService.listContainers(
            containerFilter(
                projectId = projectId,
                serviceId = serviceId
            )
        ).let {
            if (it.size > 1) {
                throw InternalDataIntegrityException("Service $serviceId has more than one container")
            }
            it.singleOrNull()
        }
    }

    private fun requestDeploymentCancellation(
        operation: ManagedServiceRuntimeOperationContext
    ): Boolean {
        val deploymentId = operation.deploymentId
            ?: return stopContainer(
                projectId = operation.projectId,
                serviceId = operation.serviceId
            )

        return when (
            val result = try {
                deploymentLifecycleService.requestDeploymentCancellation(
                    deploymentId
                )
            } catch (_: DeploymentNotFoundException) {
                throw InternalDataIntegrityException(
                    "Deployment $deploymentId captured by stop operation for service " +
                            "${operation.serviceId} disappeared"
                )
            }
        ) {
            ManagedServiceDeploymentCancellationResult.Requested -> true
            ManagedServiceDeploymentCancellationResult.NoDeploymentInProcess ->
                stopContainer(
                    projectId = operation.projectId,
                    serviceId = operation.serviceId
                )

            is ManagedServiceDeploymentCancellationResult.DeploymentFinished ->
                stopContainer(result.containerId)
        }
    }

    fun containerFilter(projectId: UUID, serviceId: UUID): DockerLabelsFilter =
        DockerLabelsFilter(
            labels = DockerLabels.forService(projectId, serviceId)
        )

    private fun failRuntimeOperation(
        operationFailure: RuntimeException,
        failTransition: () -> Unit
    ): Nothing {
        try {
            failTransition()
        } catch (transitionFailure: RuntimeException) {
            transitionFailure.addSuppressed(operationFailure)
            throw transitionFailure
        }

        throw operationFailure
    }
}
