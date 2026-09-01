package io.dockistry.platform.services.domain.dto

import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType
import io.dockistry.platform.services.domain.ManagedServiceDesiredState
import io.dockistry.platform.services.domain.ManagedServiceLifecycleStatus
import io.dockistry.platform.services.domain.ManagedServiceSecretReference
import io.dockistry.platform.services.domain.ServiceEndpoint
import io.dockistry.platform.services.domain.ServiceVolumeMount
import java.time.Instant
import java.util.UUID

data class ManagedServiceDto(
    val id: UUID,
    val projectId: UUID,
    val name: String,
    val imageRef: String,
    val command: List<String>?,
    val args: List<String>?,
    val env: Map<String, String>,
    val secretReferences: List<ManagedServiceSecretReference>,
    val endpoints: List<ServiceEndpoint>,
    val volumes: List<ServiceVolumeMount>,
    val restartPolicy: DockerRestartPolicyType,
    val restartMaxRetries: Int?,
    val memoryLimitBytes: Long?,
    val cpuLimitNanoCpus: Long?,
    val desiredState: ManagedServiceDesiredState,
    val lifecycleStatus: ManagedServiceLifecycleStatus,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant
)
