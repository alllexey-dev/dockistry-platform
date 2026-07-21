package io.dockistry.platform.services.application.dto

import io.dockistry.platform.services.domain.ManagedServiceConfiguration
import java.util.UUID

data class ManagedServiceDeploymentPlan(
    val deploymentId: UUID,
    val serviceId: UUID,
    val projectId: UUID,
    val serviceName: String,
    val snapshot: ManagedServiceConfiguration
)
