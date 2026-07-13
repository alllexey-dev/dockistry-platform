package io.dockistry.platform.services.application.dto

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.services.domain.ManagedServiceConfigurationDraft
import java.util.UUID

data class ManagedServiceCreateCommand(
    val projectId: UUID,
    val principal: Principal,
    val name: String,
    val configuration: ManagedServiceConfigurationDraft
)

data class ManagedServiceUpdateCommand(
    val projectId: UUID,
    val serviceId: UUID,
    val principal: Principal,
    val expectedVersion: Long,
    val configuration: ManagedServiceConfigurationDraft
)
