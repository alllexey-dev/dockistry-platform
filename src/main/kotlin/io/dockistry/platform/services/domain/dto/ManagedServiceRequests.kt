package io.dockistry.platform.services.domain.dto

import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType
import io.dockistry.platform.services.domain.ManagedServiceDesiredState
import io.dockistry.platform.services.domain.ServiceEndpoint
import io.dockistry.platform.services.domain.ServiceVolumeMount

data class ManagedServiceCreateRequest(
    val name: String,

    val imageRef: String,

    val command: List<String>? = null,

    val args: List<String>? = null,

    val env: Map<String, String> = emptyMap(),

    val endpoints: List<ServiceEndpoint> = emptyList(),

    val volumes: List<ServiceVolumeMount> = emptyList(),

    val restartPolicy: DockerRestartPolicyType = DockerRestartPolicyType.UNLESS_STOPPED,

    val restartMaxRetries: Int? = null,

    val memoryLimitBytes: Long? = null,

    val cpuLimitNanoCpus: Long? = null,

    val desiredState: ManagedServiceDesiredState = ManagedServiceDesiredState.RUNNING
)

data class ManagedServiceUpdateRequest(
    val expectedVersion: Long,

    val imageRef: String,

    val command: List<String>? = null,

    val args: List<String>? = null,

    val env: Map<String, String> = emptyMap(),

    val endpoints: List<ServiceEndpoint> = emptyList(),

    val volumes: List<ServiceVolumeMount> = emptyList(),

    val restartPolicy: DockerRestartPolicyType = DockerRestartPolicyType.UNLESS_STOPPED,

    val restartMaxRetries: Int? = null,

    val memoryLimitBytes: Long? = null,

    val cpuLimitNanoCpus: Long? = null,

    val desiredState: ManagedServiceDesiredState = ManagedServiceDesiredState.RUNNING
)
