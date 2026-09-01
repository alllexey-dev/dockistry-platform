package io.dockistry.platform.secrets.domain.dto

import io.dockistry.platform.secrets.domain.SecretWarningType
import java.time.Instant
import java.util.UUID

data class SecretWarningDto(
    val id: UUID,
    val warningType: SecretWarningType,
    val secretId: String,
    val secretKey: String,
    val secretVersion: String?,
    val description: String,
    val createdAt: Instant
)
