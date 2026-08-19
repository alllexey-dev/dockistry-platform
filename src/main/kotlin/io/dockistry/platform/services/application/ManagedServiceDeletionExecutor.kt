package io.dockistry.platform.services.application

import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Serializes retry-safe external cleanup through final database deletion. The lock
 * prevents a delayed retry from deleting a same-named volume created for a replacement
 * service after the original service row disappears.
 */
@Service
class ManagedServiceDeletionExecutor(
    private val deletionLifecycleService: ManagedServiceDeletionLifecycleService,
    private val managedServiceRuntimeExecutor: ManagedServiceRuntimeExecutor,
    private val projectVolumeService: ProjectVolumeService,
    private val serviceOperationGate: ManagedServiceOperationGate
) {
    fun execute(projectId: UUID, serviceId: UUID) {
        serviceOperationGate.withLock(serviceId) {
            val plan = deletionLifecycleService.loadPlan(
                projectId = projectId,
                serviceId = serviceId
            )

            try {
                managedServiceRuntimeExecutor.removeContainer(
                    projectId = plan.projectId,
                    serviceId = plan.serviceId
                )

                projectVolumeService.removeServiceVolumesByLabels(
                    projectId = plan.projectId,
                    serviceId = plan.serviceId
                )
            } catch (exception: RuntimeException) {
                deletionLifecycleService.fail(
                    projectId = plan.projectId,
                    serviceId = plan.serviceId,
                    errorMessage = exception.message
                )
                throw exception
            }

            deletionLifecycleService.complete(
                projectId = plan.projectId,
                serviceId = plan.serviceId
            )
        }
    }
}
