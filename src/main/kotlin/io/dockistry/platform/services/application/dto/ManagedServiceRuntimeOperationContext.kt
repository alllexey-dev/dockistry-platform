package io.dockistry.platform.services.application.dto

import io.dockistry.platform.services.domain.ManagedServiceLifecycleStatus
import java.util.UUID

data class ManagedServiceRuntimeOperationContext(
    val serviceId: UUID,
    val projectId: UUID,
    val expectedStatus: ManagedServiceLifecycleStatus,
    val expectedVersion: Long
)
