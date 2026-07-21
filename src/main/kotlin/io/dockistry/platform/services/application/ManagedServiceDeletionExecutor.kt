package io.dockistry.platform.services.application

import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Executes retry-safe external cleanup from a persisted deletion plan without holding
 * a database transaction open across Docker operations.
 */
@Service
class ManagedServiceDeletionExecutor(
    private val deletionLifecycleService: ManagedServiceDeletionLifecycleService,
    private val managedServiceRuntimeExecutor: ManagedServiceRuntimeExecutor,
    private val projectVolumeService: ProjectVolumeService
) {
    fun execute(projectId: UUID, serviceId: UUID) {
        val plan = deletionLifecycleService.loadPlan(
            projectId = projectId,
            serviceId = serviceId
        )

        managedServiceRuntimeExecutor.removeContainer(
            projectId = plan.projectId,
            serviceId = plan.serviceId
        )

        if (plan.removeVolumes) {
            projectVolumeService.removeServiceVolumesByLabels(
                projectId = plan.projectId,
                serviceId = plan.serviceId
            )
        }

        deletionLifecycleService.complete(
            projectId = plan.projectId,
            serviceId = plan.serviceId
        )
    }
}
