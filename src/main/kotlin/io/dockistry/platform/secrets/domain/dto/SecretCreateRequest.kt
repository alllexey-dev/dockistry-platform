package io.dockistry.platform.secrets.domain.dto

data class SecretCreateRequest(
    val name: String,
    val description: String? = null,
    val values: Map<String, String>
)
