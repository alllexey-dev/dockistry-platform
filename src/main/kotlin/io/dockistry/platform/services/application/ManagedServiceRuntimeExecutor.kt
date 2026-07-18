package io.dockistry.platform.services.application

import io.dockistry.platform.docker.domain.DockerContainerAlreadyStoppedException
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.docker.infrastructure.LocalDockerAgentClient
import io.dockistry.platform.services.domain.ManagedServiceDeploymentStatus
import io.dockistry.platform.services.domain.ManagedServiceDesiredState
import io.dockistry.platform.services.domain.ManagedServiceNotDeployedException
import io.dockistry.platform.services.domain.ManagedServiceNotFoundException
import io.dockistry.platform.services.domain.ManagedServiceNotRunningException
import io.dockistry.platform.services.infrastructure.ManagedServiceDeploymentRepository
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class ManagedServiceRuntimeExecutor(
    private val managedServiceRepository: ManagedServiceRepository,
    private val deploymentRepository: ManagedServiceDeploymentRepository,
    private val dockerAgentClient: LocalDockerAgentClient,
    private val clock: Clock
) {
    @Transactional
    fun start(serviceId: UUID) {
        val service = managedServiceRepository.findById(serviceId).orElseThrow {
            ManagedServiceNotFoundException("Managed service couldn't be found")
        }
        service.updateDesiredState(ManagedServiceDesiredState.RUNNING, clock.instant())

        val container = findServiceContainer(
            projectId = service.project.id,
            serviceId = serviceId
        )
            ?: throw ManagedServiceNotDeployedException("Service can't be started because it's not deployed")

        dockerAgentClient.startContainer(container.id)
    }

    @Transactional
    fun stop(serviceId: UUID) {
        val service = managedServiceRepository.findById(serviceId).orElseThrow {
            ManagedServiceNotFoundException("Managed service couldn't be found")
        }
        service.updateDesiredState(ManagedServiceDesiredState.STOPPED, clock.instant())

        val stopSucceeded =
            stopContainer(projectId = service.project.id, serviceId = serviceId)
                .or(
                    markDeploymentAsStopped(serviceId)
                )

        if (!stopSucceeded) {
            throw ManagedServiceNotRunningException(
                "The service can't be stopped because it is neither deployed nor being deployed"
            )
        }
    }

    fun removeContainer(projectId: UUID, serviceId: UUID) {
        findServiceContainer(
            projectId = projectId,
            serviceId = serviceId
        )?.let {
            dockerAgentClient.deleteContainer(containerId = it.id)
        }
    }

    private fun stopContainer(projectId: UUID, serviceId: UUID): Boolean {
        return findServiceContainer(
            projectId = projectId,
            serviceId = serviceId
        )
            ?.let {
                try {
                    dockerAgentClient.stopContainer(it.id)
                } catch (_: DockerContainerAlreadyStoppedException) {
                    // Target service state is already reached
                }
                true
            } ?: false
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

    private fun markDeploymentAsStopped(serviceId: UUID): Boolean {
        return deploymentRepository.findByServiceId(serviceId)
            .filter { it.status == ManagedServiceDeploymentStatus.IN_PROCESS }
            .let {
                if (it.size > 1) {
                    throw InternalDataIntegrityException("Service $serviceId has more than one deployments in process")
                }
                it.singleOrNull()
            }
            ?.let {
                it.status = ManagedServiceDeploymentStatus.CANCELLED
                it.updatedAt = clock.instant()
                true
            } ?: false
    }

    fun containerFilter(projectId: UUID, serviceId: UUID): DockerLabelsFilter =
        DockerLabelsFilter(
            labels = DockerLabels.forService(projectId, serviceId)
        )
}
