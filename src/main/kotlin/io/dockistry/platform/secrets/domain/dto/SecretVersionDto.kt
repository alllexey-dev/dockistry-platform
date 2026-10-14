package io.dockistry.platform.secrets.domain.dto

import java.time.Instant

data class SecretVersionDto(
    val version: String,
    val createdAt: Instant,
    val keys: Set<String>
)
