package io.dockistry.platform.services.application

import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceDeletionInProgressException
import io.dockistry.platform.services.domain.ManagedServiceLifecycleStatus
import io.dockistry.platform.services.domain.ManagedServiceNotFoundException
import io.dockistry.platform.services.domain.ManagedServiceOperationInProgressException
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Acquires the managed-service lifecycle mutex in the caller's transaction. The caller
 * must keep that transaction open until its state-changing operation is complete.
 */
@Service
class ManagedServiceLifecycleGuard(
    private val managedServiceRepository: ManagedServiceRepository
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun requireActiveForMutation(serviceId: UUID): ManagedService {
        val service = withOperationLock {
            managedServiceRepository.findByIdForUpdateNowait(serviceId)
        } ?: throw ManagedServiceNotFoundException("Managed service couldn't be found")

        return requireActive(service)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun requireActiveForMutation(
        projectId: UUID,
        serviceId: UUID
    ): ManagedService {
        val service = withOperationLock {
            managedServiceRepository.findByProjectIdAndIdForUpdateNowait(
                projectId = projectId,
                serviceId = serviceId
            )
        } ?: throw notFound(projectId, serviceId)

        return requireActive(service)
    }

    @Transactional(
        readOnly = true,
        propagation = Propagation.MANDATORY
    )
    fun requireAvailableForRead(
        projectId: UUID,
        serviceId: UUID
    ): ManagedService {
        val service = managedServiceRepository.findByProjectIdAndId(projectId, serviceId)
            ?: throw notFound(projectId, serviceId)

        if (service.lifecycleStatus == ManagedServiceLifecycleStatus.DELETING) {
            throw ManagedServiceDeletionInProgressException()
        }
        return service
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun requireRuntimeOperationForTransition(
        serviceId: UUID,
        expectedStatus: ManagedServiceLifecycleStatus,
        expectedVersion: Long
    ): ManagedService {
        val service = withOperationLock {
            managedServiceRepository.findByIdForUpdateNowait(serviceId)
        } ?: throw InternalDataIntegrityException(
            "Managed service $serviceId disappeared during a runtime operation"
        )

        if (
            service.lifecycleStatus != expectedStatus ||
            service.version != expectedVersion
        ) {
            throw InternalDataIntegrityException(
                "Managed service $serviceId runtime operation expected status " +
                        "$expectedStatus and version $expectedVersion, but found " +
                        "${service.lifecycleStatus} and ${service.version}"
            )
        }
        return service
    }

    private fun requireActive(service: ManagedService): ManagedService {
        return when (service.lifecycleStatus) {
            ManagedServiceLifecycleStatus.ACTIVE -> service
            ManagedServiceLifecycleStatus.DELETING ->
                throw ManagedServiceDeletionInProgressException()

            ManagedServiceLifecycleStatus.STARTING,
            ManagedServiceLifecycleStatus.STOPPING ->
                throw ManagedServiceOperationInProgressException(
                    "Managed service runtime operation ${service.lifecycleStatus} " +
                            "is currently in progress"
                )
        }
    }

    private fun withOperationLock(find: () -> ManagedService?): ManagedService? {
        return try {
            find()
        } catch (_: PessimisticLockingFailureException) {
            throw ManagedServiceOperationInProgressException()
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
