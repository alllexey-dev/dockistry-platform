package io.dockistry.platform.services.application

import io.dockistry.platform.services.application.dto.ManagedServiceRuntimeOperationContext
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceLifecycleStatus
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Persists runtime-operation ownership before Docker I/O and finalizes it in a separate
 * transaction, keeping the service row unlocked while the external call is in flight.
 */
@Service
class ManagedServiceRuntimeLifecycleService(
    private val clock: Clock,
    private val managedServiceLifecycleGuard: ManagedServiceLifecycleGuard,
    private val managedServiceRepository: ManagedServiceRepository
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun beginStart(serviceId: UUID): ManagedServiceRuntimeOperationContext {
        val service = managedServiceLifecycleGuard
            .requireActiveForMutationWithWait(serviceId)
        service.beginStart(clock.instant())
        return operationContext(
            service = managedServiceRepository.saveAndFlush(service),
            expectedStatus = ManagedServiceLifecycleStatus.STARTING
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeStart(operation: ManagedServiceRuntimeOperationContext) {
        val service = requireOperation(
            operation = operation,
            expectedStatus = ManagedServiceLifecycleStatus.STARTING
        )
        service.completeStart(clock.instant())
        managedServiceRepository.saveAndFlush(service)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failStart(operation: ManagedServiceRuntimeOperationContext) {
        val service = requireOperation(
            operation = operation,
            expectedStatus = ManagedServiceLifecycleStatus.STARTING
        )
        service.failStart(clock.instant())
        managedServiceRepository.saveAndFlush(service)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun beginStop(serviceId: UUID): ManagedServiceRuntimeOperationContext {
        val service = managedServiceLifecycleGuard
            .requireActiveForMutationWithWait(serviceId)
        service.beginStop(clock.instant())
        return operationContext(
            service = managedServiceRepository.saveAndFlush(service),
            expectedStatus = ManagedServiceLifecycleStatus.STOPPING
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeStop(operation: ManagedServiceRuntimeOperationContext) {
        val service = requireOperation(
            operation = operation,
            expectedStatus = ManagedServiceLifecycleStatus.STOPPING
        )
        service.completeStop(clock.instant())
        managedServiceRepository.saveAndFlush(service)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failStop(operation: ManagedServiceRuntimeOperationContext) {
        val service = requireOperation(
            operation = operation,
            expectedStatus = ManagedServiceLifecycleStatus.STOPPING
        )
        service.failStop(clock.instant())
        managedServiceRepository.saveAndFlush(service)
    }

    private fun requireOperation(
        operation: ManagedServiceRuntimeOperationContext,
        expectedStatus: ManagedServiceLifecycleStatus
    ): ManagedService {
        if (operation.expectedStatus != expectedStatus) {
            throw InternalDataIntegrityException(
                "Managed service ${operation.serviceId} runtime operation context has " +
                        "status ${operation.expectedStatus} instead of $expectedStatus"
            )
        }

        return managedServiceLifecycleGuard.requireRuntimeOperationForTransitionWithWait(
            serviceId = operation.serviceId,
            expectedStatus = expectedStatus,
            expectedVersion = operation.expectedVersion
        )
    }

    private fun operationContext(
        service: ManagedService,
        expectedStatus: ManagedServiceLifecycleStatus
    ): ManagedServiceRuntimeOperationContext {
        return ManagedServiceRuntimeOperationContext(
            serviceId = service.id,
            projectId = service.project.id,
            expectedStatus = expectedStatus,
            expectedVersion = service.version
        )
    }
}
