package io.dockistry.platform.services.domain

data class ServiceVolumeMount(
    val name: String,

    val mountPath: String,

    val readOnly: Boolean = false
)
