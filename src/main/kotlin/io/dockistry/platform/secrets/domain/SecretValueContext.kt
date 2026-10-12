package io.dockistry.platform.secrets.domain

/** Identifies the exact cell an encrypted Secret value belongs to. */
data class SecretValueContext(
    val secretId: String,
    val versionId: String,
    val key: String
)
