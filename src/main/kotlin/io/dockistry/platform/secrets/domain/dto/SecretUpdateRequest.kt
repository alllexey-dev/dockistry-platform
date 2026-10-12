package io.dockistry.platform.secrets.domain.dto

data class SecretUpdateRequest(
    val name: String,
    val description: String? = null,
    val expectedVersion: Long
)
