package io.dockistry.platform.services.domain

data class ManagedServiceSecretReference(
    val secretId: String,
    val key: String,
    val envName: String,
    /** Null resolves to the latest available version at deploy time. */
    val secretVersion: String? = null
)
