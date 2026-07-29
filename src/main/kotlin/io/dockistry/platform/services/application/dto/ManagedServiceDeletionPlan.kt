package io.dockistry.platform.services.application.dto

import java.util.UUID

data class ManagedServiceDeletionPlan(
    val projectId: UUID,
    val serviceId: UUID
)
