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
import java.time.Duration
import java.util.UUID

/**
 * Acquires the managed-service lifecycle mutex in the caller's transaction. The caller
 * must keep that transaction open until its state-changing operation is complete.
 */
@Service
class ManagedServiceLifecycleGuard(
    private val managedServiceRepository: ManagedServiceRepository
) {
    companion object {
        val DEFAULT_LOCK_WAIT_TIMEOUT: Duration = Duration.ofSeconds(1)
    }

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

    @Transactional(propagation = Propagation.MANDATORY)
    fun requireActiveForMutationWithWait(
        serviceId: UUID,
        lockTimeout: Duration = DEFAULT_LOCK_WAIT_TIMEOUT
    ): ManagedService {
        validateLockTimeout(lockTimeout)
        val service = withOperationLock {
            managedServiceRepository.findByIdForUpdate(
                serviceId = serviceId,
                lockTimeout = lockTimeout
            )
        } ?: throw ManagedServiceNotFoundException("Managed service couldn't be found")

        return requireActive(service)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun requireActiveForMutationWithWait(
        projectId: UUID,
        serviceId: UUID,
        lockTimeout: Duration = DEFAULT_LOCK_WAIT_TIMEOUT
    ): ManagedService {
        validateLockTimeout(lockTimeout)
        val service = withOperationLock {
            managedServiceRepository.findByProjectIdAndIdForUpdate(
                projectId = projectId,
                serviceId = serviceId,
                lockTimeout = lockTimeout
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

        requireRuntimeOperationState(
            service = service,
            expectedStatus = expectedStatus,
            expectedVersion = expectedVersion
        )
        return service
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun requireRuntimeOperationForTransitionWithWait(
        serviceId: UUID,
        expectedStatus: ManagedServiceLifecycleStatus,
        expectedVersion: Long,
        lockTimeout: Duration = DEFAULT_LOCK_WAIT_TIMEOUT
    ): ManagedService {
        validateLockTimeout(lockTimeout)
        val service = withOperationLock {
            managedServiceRepository.findByIdForUpdate(
                serviceId = serviceId,
                lockTimeout = lockTimeout
            )
        } ?: throw InternalDataIntegrityException(
            "Managed service $serviceId disappeared during a runtime operation"
        )

        requireRuntimeOperationState(
            service = service,
            expectedStatus = expectedStatus,
            expectedVersion = expectedVersion
        )
        return service
    }

    private fun requireActive(service: ManagedService): ManagedService {
        return when (service.lifecycleStatus) {
            ManagedServiceLifecycleStatus.ACTIVE -> service
            ManagedServiceLifecycleStatus.DELETING ->
                throw ManagedServiceDeletionInProgressException()

            ManagedServiceLifecycleStatus.STARTING,
            ManagedServiceLifecycleStatus.STOPPING,
            ManagedServiceLifecycleStatus.PAUSING,
            ManagedServiceLifecycleStatus.UNPAUSING ->
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

    private fun validateLockTimeout(lockTimeout: Duration) {
        if (lockTimeout.isNegative || lockTimeout.isZero || lockTimeout.toMillis() == 0L) {
            throw InternalDataIntegrityException(
                "Managed service lifecycle lock timeout must be at least one millisecond"
            )
        }
    }

    private fun requireRuntimeOperationState(
        service: ManagedService,
        expectedStatus: ManagedServiceLifecycleStatus,
        expectedVersion: Long
    ) {
        if (
            service.lifecycleStatus != expectedStatus ||
            service.version != expectedVersion
        ) {
            throw InternalDataIntegrityException(
                "Managed service ${service.id} runtime operation expected status " +
                        "$expectedStatus and version $expectedVersion, but found " +
                        "${service.lifecycleStatus} and ${service.version}"
            )
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
