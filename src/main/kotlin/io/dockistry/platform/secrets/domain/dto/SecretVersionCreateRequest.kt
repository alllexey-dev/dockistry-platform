package io.dockistry.platform.secrets.domain.dto

/** Values for a new immutable Secret version (initial version or rotation). */
data class SecretVersionCreateRequest(
    val values: Map<String, String>
)
