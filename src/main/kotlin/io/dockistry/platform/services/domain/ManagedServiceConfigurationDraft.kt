package io.dockistry.platform.services.domain

import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType

data class ManagedServiceConfigurationDraft(
    val imageRef: String,
    val command: List<String>?,
    val args: List<String>?,
    val env: Map<String, String>,
    val endpoints: List<ServiceEndpoint>,
    val volumes: List<ServiceVolumeMount>,
    val restartPolicy: DockerRestartPolicyType,
    val restartMaxRetries: Int?,
    val memoryLimitBytes: Long?,
    val cpuLimitNanoCpus: Long?,
    val desiredState: ManagedServiceDesiredState
)
