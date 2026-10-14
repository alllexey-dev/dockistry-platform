package io.dockistry.platform.services.application.dto

import io.dockistry.platform.secrets.domain.SecretPayload
import io.dockistry.platform.services.domain.ManagedServiceConfiguration
import java.util.UUID

data class ManagedServiceDeploymentPlan(
    val deploymentId: UUID,
    val serviceId: UUID,
    val projectId: UUID,
    val projectSlug: String,
    val serviceName: String,
    val snapshot: ManagedServiceConfiguration,
    val secretPayloads: List<SecretPayload>,
    val acceptedWarningIds: List<UUID>
)
