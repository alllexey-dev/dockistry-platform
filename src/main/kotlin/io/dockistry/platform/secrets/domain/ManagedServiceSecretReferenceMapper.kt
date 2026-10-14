package io.dockistry.platform.secrets.domain

import io.dockistry.platform.services.domain.ManagedServiceSecretReference

fun ManagedServiceSecretReferenceEntity.toDto() = ManagedServiceSecretReference(
    secretId = secret.id,
    key = secretKey,
    envName = envName,
    secretVersion = secretVersion
)
