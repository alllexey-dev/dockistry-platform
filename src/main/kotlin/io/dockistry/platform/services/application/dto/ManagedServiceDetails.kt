package io.dockistry.platform.services.application.dto

import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceSecretReference

data class ManagedServiceDetails(
    val service: ManagedService,
    val secretReferences: List<ManagedServiceSecretReference>
)
