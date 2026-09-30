package io.dockistry.platform.services.application.dto

import io.dockistry.platform.secrets.domain.dto.SecretWarningDto
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceSecretReference

data class ManagedServiceDetails(
    val service: ManagedService,
    val secretReferences: List<ManagedServiceSecretReference>,
    val warnings: List<SecretWarningDto>
)
