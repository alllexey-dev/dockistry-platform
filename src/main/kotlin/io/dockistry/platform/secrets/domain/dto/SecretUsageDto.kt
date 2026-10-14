package io.dockistry.platform.secrets.domain.dto

import java.util.UUID

/** One configuration dependency: a Managed Service environment variable backed by a Secret. */
data class SecretUsageDto(
    val projectId: UUID,
    val projectName: String,
    val serviceId: UUID,
    val serviceName: String,
    val secretId: String,
    val key: String,
    val envName: String,
    val secretVersion: String?
)
