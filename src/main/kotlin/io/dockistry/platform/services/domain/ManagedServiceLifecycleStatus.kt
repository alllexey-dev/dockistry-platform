package io.dockistry.platform.services.domain

enum class ManagedServiceLifecycleStatus {
    ACTIVE,
    STARTING,
    STOPPING,
    DELETING
}
