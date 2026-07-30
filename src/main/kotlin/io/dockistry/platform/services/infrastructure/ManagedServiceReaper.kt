package io.dockistry.platform.services.infrastructure

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.model.DockerContainerState
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.services.application.ManagedServiceDeploymentCancellationResult
import io.dockistry.platform.services.application.ManagedServiceDeploymentCleanupService
import io.dockistry.platform.services.application.ManagedServiceDeploymentFinalizationResult
import io.dockistry.platform.services.application.ManagedServiceDeploymentLifecycleService
import io.dockistry.platform.services.application.ManagedServiceDeploymentOperationGate
import io.dockistry.platform.services.application.ManagedServiceDeletionExecutor
import io.dockistry.platform.services.application.ManagedServiceOperationGate
import io.dockistry.platform.services.domain.DeploymentNotFoundException
import io.dockistry.platform.services.domain.ManagedServiceDeployment
import io.dockistry.platform.services.domain.ManagedServiceDeploymentStatus
import io.dockistry.platform.services.domain.ManagedServiceExecutionCapacityExhaustedException
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

@Service
class ManagedServiceReaper(
    private val clock: Clock,
    private val deploymentRepository: ManagedServiceDeploymentRepository,
    private val deploymentLifecycleService: ManagedServiceDeploymentLifecycleService,
    private val deploymentCleanupService: ManagedServiceDeploymentCleanupService,
    private val dockerAgentClient: DockerAgentClient,
    private val serviceRepository: ManagedServiceRepository,
    private val deletionExecutor: ManagedServiceDeletionExecutor,
    private val properties: ManagedServiceReaperProperties,
    private val deploymentOperationGate: ManagedServiceDeploymentOperationGate,
    private val serviceOperationGate: ManagedServiceOperationGate
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ManagedServiceReaper::class.java)
    }

    fun execute() {
        val updatedBefore = clock.instant().minus(properties.deploymentTimeout)

        processDeploymentActions(
            deploymentRepository.findStuckDeploymentsWithProjectId(
                updatedBefore,
                properties.deploymentBatchLimit
            )
                .mapNotNull { deployment ->
                    when (deployment.status) {
                        ManagedServiceDeploymentStatus.IN_PROCESS -> try {
                            decideActionForInProcessDeployment(
                                deployment
                            )
                        } catch (exception: RuntimeException) {
                            logger.warn(
                                "Failed to pick reaper action for deployment {}: {}",
                                deployment.id,
                                exception.message,
                                exception
                            )
                            null
                        }

                        ManagedServiceDeploymentStatus.CANCELING -> DeploymentReaperAction(
                            projectId = deployment.service.project.id,
                            serviceId = deployment.service.id,
                            deploymentId = deployment.id,
                            type = DeploymentReaperActionType.CleanupAndCancel
                        )

                        else -> {
                            logger.warn(
                                "Deployment {} with status {} is considered stuck by deployment repo",
                                deployment.id,
                                deployment.status
                            )
                            null
                        }
                    }
                }
        )

        processStuckServices()
    }

    private fun processDeploymentActions(actions: Collection<DeploymentReaperAction>) {
        val finishedResult = processMarkFinished(actions)

        processMarkCancelingAndCleanup(actions)
        processCleanupAndCancel(actions + finishedResult.returnedForCleanupAndCancel)
    }

    private fun processStuckServices() {
        processStuckRuntimeOperations()
        processStuckDeletingServices()
    }

    private fun processStuckRuntimeOperations() {
        val current = clock.instant()
        val stuckOperations = serviceRepository.findStuckRuntimeOperations(
            updatedBefore = current.minus(properties.runtimeOperationTimeout),
            batchLimit = properties.runtimeOperationBatchLimit
        )

        stuckOperations.forEach { operation ->
            try {
                serviceOperationGate.withLock(
                    serviceId = operation.id,
                    timeout = properties.serviceOperationGateTimeout
                ) {
                    serviceRepository.setRuntimeOperationToActiveIfVersion(
                        serviceId = operation.id,
                        expectedVersion = operation.version,
                        updatedAt = current
                    )
                }
            } catch (exception: ManagedServiceExecutionCapacityExhaustedException) {
                logger.warn(
                    "Service operation gate's capacity is currently exhausted, quitting processing stuck runtime operations {}",
                    exception.message,
                    exception
                )
                return
            } catch (exception: RuntimeException) {
                logger.warn(
                    "Failed to reap stuck runtime operation for service {}: {}",
                    operation.id,
                    exception.message,
                    exception
                )
            }
        }
    }

    private fun processStuckDeletingServices() {
        val updatedBefore = clock.instant().minus(properties.deletingServiceTimeout)
        serviceRepository.findStuckDeletingServices(
            updatedBefore = updatedBefore,
            batchLimit = properties.deletingServiceBatchLimit
        ).forEach { service ->
            try {
                deletionExecutor.execute(
                    projectId = service.project.id,
                    serviceId = service.id
                )
            } catch (exception: RuntimeException) {
                logger.warn(
                    "Failed to delete stuck deleting service {}: {}",
                    service.id,
                    exception.message,
                    exception
                )
            }
        }
    }

    private fun processCleanupAndCancel(
        actions: Collection<DeploymentReaperAction>
    ) {
        cleanup(
            actions.filter { action ->
                action.type == DeploymentReaperActionType.CleanupAndCancel
            }
        )
    }

    private fun processMarkCancelingAndCleanup(actions: Collection<DeploymentReaperAction>) {
        val markCancelingActions =
            actions.filter { it.type == DeploymentReaperActionType.MarkCancelingAndCleanup }

        val markedCanceling = markCancelingActions.filter { action ->
            try {
                deploymentOperationGate.withLock(action.deploymentId) {
                    val container = getContainerSummary(
                        projectId = action.projectId,
                        serviceId = action.serviceId,
                        deploymentId = action.deploymentId
                    )

                    if (container != null && container.state != DockerContainerState.CREATED) {
                        return@withLock false
                    }

                    deploymentLifecycleService.requestDeploymentCancellation(
                        deploymentId = action.deploymentId
                    ) == ManagedServiceDeploymentCancellationResult.Requested
                }
            } catch (exception: DeploymentNotFoundException) {
                logger.warn(
                    "Deployment {} disappeared before a cancellation could be requested",
                    action.deploymentId,
                    exception
                )
                false
            } catch (exception: RuntimeException) {
                logger.warn(
                    "Deployment {} encountered error while requesting cancellation: {}",
                    action.deploymentId,
                    exception.message,
                    exception
                )
                false
            }
        }

        cleanup(markedCanceling)
    }

    private fun cleanup(toCancel: Collection<DeploymentReaperAction>) {
        toCancel.forEach { action ->
            try {
                deploymentCleanupService.cleanup(
                    projectId = action.projectId,
                    serviceId = action.serviceId,
                    deploymentId = action.deploymentId
                )
            } catch (exception: RuntimeException) {
                logger.warn(
                    "Deployment cleanup failed for deployment {}: {}",
                    action.deploymentId,
                    exception.message,
                    exception
                )
            }
        }
    }

    private fun processMarkFinished(actions: Collection<DeploymentReaperAction>): MarkForFinishedResult {
        val markFinishedActions = actions.mapNotNull { action ->
            val type = action.type as? DeploymentReaperActionType.MarkFinished
                ?: return@mapNotNull null

            action to type
        }

        val requireCleanupAndCancel = mutableListOf<DeploymentReaperAction>()
        try {
            markFinishedActions.forEach { (action, type) ->
                try {
                    deploymentOperationGate.withLock(action.deploymentId) {
                        val container = getContainerSummary(
                            projectId = action.projectId,
                            serviceId = action.serviceId,
                            deploymentId = action.deploymentId
                        ) ?: return@withLock

                        if (container.id != type.containerId
                            || container.state == DockerContainerState.UNKNOWN
                            || container.state == DockerContainerState.CREATED
                        ) {
                            return@withLock
                        }

                        val finishResult = deploymentLifecycleService.finish(
                            deploymentId = action.deploymentId,
                            containerId = type.containerId
                        )

                        if (finishResult ==
                            ManagedServiceDeploymentFinalizationResult.CANCELLATION_REQUESTED
                        ) {

                            requireCleanupAndCancel.add(
                                DeploymentReaperAction(
                                    projectId = action.projectId,
                                    serviceId = action.serviceId,
                                    deploymentId = action.deploymentId,
                                    type = DeploymentReaperActionType.CleanupAndCancel
                                )
                            )
                        }
                    }
                } catch (exception: InternalDataIntegrityException) {
                    logger.warn(
                        "Internal data integrity exception encountered while marking deployment {} finished: {}",
                        action.deploymentId,
                        exception.message,
                        exception
                    )
                }
            }
        } catch (exception: RuntimeException) {
            logger.warn(
                "Failed to mark deployments finished: {}",
                exception.message,
                exception
            )
        }

        return MarkForFinishedResult(requireCleanupAndCancel)
    }

    private fun getContainerSummary(
        projectId: UUID,
        serviceId: UUID,
        deploymentId: UUID
    ): DockerContainerSummary? {
        return dockerAgentClient.listContainers(
            DockerLabelsFilter(
                DockerLabels.forDeployment(
                    projectId = projectId,
                    serviceId = serviceId,
                    deploymentId = deploymentId
                )
            )
        ).also {
            if (it.size > 1) {
                logger.warn(
                    "Docker agent client returned {} containers for deployment {} Docker labels",
                    it.size,
                    deploymentId
                )
                throw InternalDataIntegrityException("Docker agent client returned ${it.size} containers for $deploymentId deployment's Docker labels")
            }
        }.singleOrNull()
    }

    private fun decideActionForInProcessDeployment(deployment: ManagedServiceDeployment):
            DeploymentReaperAction? {
        val container = getContainerSummary(
            projectId = deployment.service.project.id,
            serviceId = deployment.service.id,
            deploymentId = deployment.id
        )

        if (container?.state == DockerContainerState.UNKNOWN) {
            logger.warn(
                "Container {} of deployment {} is in state {} and is skipped for reaper action",
                container.id,
                deployment.id,
                container.state
            )
            return null
        }

        return DeploymentReaperAction(
            projectId = deployment.service.project.id,
            serviceId = deployment.service.id,
            deploymentId = deployment.id,
            type =
                if (container != null && container.state != DockerContainerState.CREATED)
                    DeploymentReaperActionType.MarkFinished(
                        container.id
                    )
                else DeploymentReaperActionType.MarkCancelingAndCleanup
        )
    }

    private data class DeploymentReaperAction(
        val projectId: UUID,
        val serviceId: UUID,
        val deploymentId: UUID,
        val type: DeploymentReaperActionType
    )

    private sealed interface DeploymentReaperActionType {
        data class MarkFinished(val containerId: String) : DeploymentReaperActionType
        data object MarkCancelingAndCleanup : DeploymentReaperActionType
        data object CleanupAndCancel : DeploymentReaperActionType
    }

    private data class MarkForFinishedResult(
        val returnedForCleanupAndCancel: List<DeploymentReaperAction>
    )
}
