package io.dockistry.platform.docker.domain.dto

import jakarta.validation.constraints.NotBlank

data class DockerAgentStatus(
    val state: DockerAgentState,
    val engineVersion: String? = null,
    val apiVersion: String? = null,
    val operatingSystem: String? = null,
    val errorMessage: String? = null
)

enum class DockerAgentState {
    AVAILABLE,
    UNAVAILABLE
}

data class PullImageRequest(
    @field:NotBlank
    val repository: String,

    @field:NotBlank
    val tag: String
)

data class ImageSummary(
    val id: String,
    val reference: String?,
    val registry: ImageRegistryInfo?,
    val namespace: String?,
    val repository: String?,
    val tag: String?,
    val repoDigests: List<String>,
    val createdEpochSeconds: Long?,
    val sizeBytes: Long?,
    val labels: Map<String, String>
)

data class ImageInfo(
    val id: String,
    val parentId: String?,
    val repoTags: List<ImageReference>,
    val repoDigests: List<String>,
    val createdAt: String?,
    val dockerVersion: String?,
    val os: String?,
    val osVersion: String?,
    val architecture: String?,
    val author: String?,
    val comment: String?,
    val sizeBytes: Long?,
    val virtualSizeBytes: Long?,
    val rootFs: ImageRootFs?,
    val config: ImageConfig?,
    val containerConfig: ImageConfig?
)

data class ImageReference(
    val raw: String,
    val registry: ImageRegistryInfo?,
    val namespace: String?,
    val repository: String?,
    val tag: String?
)

data class ImageRegistryInfo(
    val address: String,
    val port: Int? = null
)

data class ImageConfig(
    val image: String?,
    val envNames: List<String>,
    val cmd: List<String>,
    val entrypoint: List<String>,
    val workingDir: String?,
    val user: String?,
    val labels: Map<String, String>,
    val exposedPorts: List<ExposedPortInfo>
)

data class ImageRootFs(
    val type: String?,
    val layers: List<String>
)

data class ContainerSummary(
    val id: String,
    val name: String?,
    val image: String?,
    val state: ContainerState,
    val status: String?,
    val ports: List<PortInfo>,
    val labels: Map<String, String>
)

data class ContainerDetails(
    val id: String,
    val name: String?,
    val image: String?,
    val state: ContainerState,
    val ports: List<PortInfo>,
    val labels: Map<String, String>,
    val envNames: List<String>,
    val mounts: List<MountInfo>,
    val restartPolicy: RestartPolicy
)

enum class ContainerState {
    CREATED,
    RUNNING,
    PAUSED,
    RESTARTING,
    REMOVING,
    EXITED,
    DEAD,
    UNKNOWN
}

data class PortInfo(
    val privatePort: Int?,
    val publicPort: Int?,
    val ip: String?,
    val type: String?
)

data class MountInfo(
    val type: String?,
    val source: String?,
    val target: String?,
    val readOnly: Boolean?
)

data class RestartPolicy(
    val type: RestartPolicyType,
    val maxRetries: Int? = null
)

enum class RestartPolicyType {
    NO,
    ALWAYS,
    ON_FAILURE,
    UNLESS_STOPPED
}

data class ExposedPortInfo(
    val port: Int,
    val type: String
)

data class ContainerStats(
    val cpuPercent: Double?,
    val memoryUsageBytes: Long?,
    val memoryLimitBytes: Long?,
    val networkRxBytes: Long?,
    val networkTxBytes: Long?,
    val diskReadBytes: Long?,
    val diskWriteBytes: Long?
)

data class ContainerLogs(
    val lines: List<ContainerLogLine>
)

data class ContainerLogLine(
    val stream: ContainerLogStream,
    val line: String
)

enum class ContainerLogStream {
    STDOUT,
    STDERR,
    RAW
}
