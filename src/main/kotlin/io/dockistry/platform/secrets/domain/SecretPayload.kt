package io.dockistry.platform.secrets.domain

class SecretPayload(
    val envName: String,
    val encryptedPayload: String
)