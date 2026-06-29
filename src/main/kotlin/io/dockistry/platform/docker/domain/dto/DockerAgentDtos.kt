package io.dockistry.platform.docker.domain.dto

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

data class ContainerFilter(
    val labels: Map<String, String> = emptyMap()
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
    val status: String?,
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

data class ContainerStats(
    val cpuPercent: Double?,
    val memoryUsageBytes: Long?,
    val memoryLimitBytes: Long?,
    val networkRxBytes: Long?,
    val networkTxBytes: Long?
)
