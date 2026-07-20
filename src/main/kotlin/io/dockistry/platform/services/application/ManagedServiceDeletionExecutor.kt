package io.dockistry.platform.services.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.services.domain.ManagedServiceDeletionBlockedByDeployment
import io.dockistry.platform.services.domain.ManagedServiceNotFoundException
import io.dockistry.platform.services.infrastructure.ManagedServiceDeploymentRepository
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Commits the deletion boundary before external cleanup starts, so every service
 * mutation observes either the row lock or the durable DELETING state.
 */
@Service
class ManagedServiceDeletionExecutor(
    private val clock: Clock,
    private val projectAccessGuard: ProjectAccessGuard,
    private val managedServiceLifecycleGuard: ManagedServiceLifecycleGuard,
    private val managedServiceRepository: ManagedServiceRepository,
    private val deploymentRepository: ManagedServiceDeploymentRepository
) {
    @Transactional
    fun begin(projectId: UUID, serviceId: UUID, principal: Principal) {
        projectAccessGuard.requireServicesManageable(projectId, principal)

        val service = managedServiceLifecycleGuard.requireActiveForMutation(
            projectId = projectId,
            serviceId = serviceId
        )

        if (deploymentRepository.hasActiveDeployments(serviceId)) {
            throw ManagedServiceDeletionBlockedByDeployment(
                "This service is currently being deployed"
            )
        }

        service.beginDeletion(clock.instant())
        managedServiceRepository.saveAndFlush(service)
    }

    @Transactional
    fun complete(projectId: UUID, serviceId: UUID) {
        val deletedRows = managedServiceRepository.deleteDeletingByProjectIdAndId(
            projectId = projectId,
            serviceId = serviceId
        )

        if (deletedRows == 0) {
            throw notFound(projectId, serviceId)
        }
    }

    private fun notFound(
        projectId: UUID,
        serviceId: UUID
    ): ManagedServiceNotFoundException {
        return ManagedServiceNotFoundException(
            "Managed service $serviceId couldn't be found in project $projectId"
        )
    }
}
