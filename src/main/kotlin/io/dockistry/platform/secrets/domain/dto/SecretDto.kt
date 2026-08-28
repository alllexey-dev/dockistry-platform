package io.dockistry.platform.secrets.domain.dto

import java.time.Instant

data class SecretDto(
    val id: String,
    val name: String,
    val description: String?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val keys: Set<String>
)
