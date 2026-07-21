package io.dockistry.platform.services.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.services.application.dto.ManagedServiceDeletionPlan
import io.dockistry.platform.services.domain.ManagedServiceDeletionBlockedByDeployment
import io.dockistry.platform.services.domain.ManagedServiceLifecycleStatus
import io.dockistry.platform.services.domain.ManagedServiceNotFoundException
import io.dockistry.platform.services.infrastructure.ManagedServiceDeploymentRepository
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Owns the short database transactions around the durable managed-service deletion
 * lifecycle, leaving external cleanup to [ManagedServiceDeletionExecutor].
 */
@Service
class ManagedServiceDeletionLifecycleService(
    private val clock: Clock,
    private val projectAccessGuard: ProjectAccessGuard,
    private val managedServiceLifecycleGuard: ManagedServiceLifecycleGuard,
    private val managedServiceRepository: ManagedServiceRepository,
    private val deploymentRepository: ManagedServiceDeploymentRepository
) {
    @Transactional
    fun begin(
        projectId: UUID,
        serviceId: UUID,
        principal: Principal,
        removeVolumes: Boolean
    ) {
        projectAccessGuard.requireServicesManageable(projectId, principal)

        val service = managedServiceLifecycleGuard.requireActiveForMutationWithWait(
            projectId = projectId,
            serviceId = serviceId
        )

        if (deploymentRepository.hasActiveDeployments(serviceId)) {
            throw ManagedServiceDeletionBlockedByDeployment(
                "This service is currently being deployed"
            )
        }

        service.beginDeletion(
            removeVolumes = removeVolumes,
            updatedAt = clock.instant()
        )
        managedServiceRepository.saveAndFlush(service)
    }

    @Transactional(readOnly = true)
    fun loadPlan(projectId: UUID, serviceId: UUID): ManagedServiceDeletionPlan {
        val service = managedServiceRepository.findByProjectIdAndId(projectId, serviceId)
            ?: throw notFound(projectId, serviceId)

        if (service.lifecycleStatus != ManagedServiceLifecycleStatus.DELETING) {
            throw InternalDataIntegrityException(
                "Managed service $serviceId deletion plan was requested while its " +
                    "lifecycle status is ${service.lifecycleStatus}"
            )
        }

        val deletionContext = service.deletionContext
            ?: throw InternalDataIntegrityException(
                "Deleting managed service $serviceId has no deletion context"
            )

        return ManagedServiceDeletionPlan(
            projectId = service.project.id,
            serviceId = service.id,
            removeVolumes = deletionContext.removeVolumes
        )
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
