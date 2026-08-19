package io.dockistry.platform.services.infrastructure

import io.dockistry.platform.services.domain.ManagedService
import java.time.Duration
import java.util.UUID

interface ManagedServiceWaitingLockRepository {
    fun findByIdForUpdate(
        serviceId: UUID,
        lockTimeout: Duration
    ): ManagedService?

    fun findByProjectIdAndIdForUpdate(
        projectId: UUID,
        serviceId: UUID,
        lockTimeout: Duration
    ): ManagedService?
}
