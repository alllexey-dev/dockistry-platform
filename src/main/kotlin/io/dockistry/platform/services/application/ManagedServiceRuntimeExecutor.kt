package io.dockistry.platform.services.application

import io.dockistry.platform.docker.domain.DockerContainerAlreadyStoppedException
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.docker.infrastructure.LocalDockerAgentClient
import io.dockistry.platform.services.domain.ManagedServiceNotDeployedException
import io.dockistry.platform.services.domain.ManagedServiceNotRunningException
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ManagedServiceRuntimeExecutor(
    private val runtimeLifecycleService: ManagedServiceRuntimeLifecycleService,
    private val deploymentLifecycleService: ManagedServiceDeploymentLifecycleService,
    private val dockerAgentClient: LocalDockerAgentClient
) {
    fun start(serviceId: UUID) {
        val operation = runtimeLifecycleService.beginStart(serviceId)

        try {
            val container = findServiceContainer(
                projectId = operation.projectId,
                serviceId = serviceId
            ) ?: throw ManagedServiceNotDeployedException(
                "Service can't be started because it's not deployed"
            )

            dockerAgentClient.startContainer(container.id)
        } catch (exception: RuntimeException) {
            failRuntimeOperation(exception) {
                runtimeLifecycleService.failStart(operation)
            }
        }

        runtimeLifecycleService.completeStart(operation)
    }

    fun stop(serviceId: UUID) {
        val operation = runtimeLifecycleService.beginStop(serviceId)

        try {
            val stopSucceeded =
                stopContainer(projectId = operation.projectId, serviceId = serviceId)
                    || requestDeploymentCancellation(
                        projectId = operation.projectId,
                        serviceId = serviceId
                    )

            if (!stopSucceeded) {
                throw ManagedServiceNotRunningException(
                    "The service can't be stopped because it is neither deployed nor being deployed"
                )
            }
        } catch (exception: RuntimeException) {
            failRuntimeOperation(exception) {
                runtimeLifecycleService.failStop(operation)
            }
        }

        runtimeLifecycleService.completeStop(operation)
    }

    fun removeContainer(projectId: UUID, serviceId: UUID) {
        findServiceContainer(
            projectId = projectId,
            serviceId = serviceId
        )?.let {
            try {
                dockerAgentClient.deleteContainer(containerId = it.id)
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
            dockerAgentClient.stopContainer(containerId)
        } catch (_: DockerContainerAlreadyStoppedException) {
            // Target service state is already reached
        }
        return true
    }

    private fun findServiceContainer(
        projectId: UUID,
        serviceId: UUID
    ): DockerContainerSummary? {
        return dockerAgentClient.listContainers(
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
        projectId: UUID,
        serviceId: UUID
    ): Boolean {
        return when (
            val result = deploymentLifecycleService.requestDeploymentCancellation(serviceId)
        ) {
            ManagedServiceDeploymentCancellationResult.Requested -> true
            ManagedServiceDeploymentCancellationResult.NoDeploymentInProcess ->
                stopContainer(projectId = projectId, serviceId = serviceId)

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
