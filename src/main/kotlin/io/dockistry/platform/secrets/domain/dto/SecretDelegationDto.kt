package io.dockistry.platform.secrets.domain.dto

import java.time.Instant
import java.util.UUID

data class SecretDelegationDto(
    val projectId: UUID,
    val projectName: String,
    val createdAt: Instant
)
