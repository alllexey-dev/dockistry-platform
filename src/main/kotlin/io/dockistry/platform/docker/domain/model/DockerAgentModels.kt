package io.dockistry.platform.docker.domain.model

import io.dockistry.platform.docker.domain.InvalidContainerLogsRequestException
import java.time.Instant

data class DockerAgentInfo(
    val state: DockerAgentAvailability,
    val engineVersion: String? = null,
    val apiVersion: String? = null,
    val operatingSystem: String? = null,
    val architecture: String? = null,
    val serverName: String? = null,
    val dockerRootDir: String? = null,
    val cgroupDriver: String? = null,
    val cgroupVersion: String? = null,
    val errorMessage: String? = null
)

enum class DockerAgentAvailability {
    AVAILABLE,
    UNAVAILABLE
}

data class DockerLabelsFilter(
    val labels: Map<String, String> = emptyMap()
)

data class DockerVolumeRef(
    val name: String,
    val labels: Map<String, String>
)

data class DockerImageRef(
    val repository: String,
    val tag: String
)

data class DockerImageRegistryInfo(
    val address: String,
    val port: Int? = null
)

data class DockerImageReference(
    val raw: String,
    val registry: DockerImageRegistryInfo?,
    val namespace: String?,
    val repository: String?,
    val tag: String?
)

data class DockerImageSummary(
    val id: String,
    val references: List<DockerImageReference>,
    val repoDigests: List<String>,
    val parentId: String?,
    val createdEpochSeconds: Long?,
    val sizeBytes: Long?,
    val labels: Map<String, String>
)

data class DockerImageInfo(
    val id: String,
    val parentId: String?,
    val repoTags: List<DockerImageReference>,
    val repoDigests: List<String>,
    val createdAt: String?,
    val dockerVersion: String?,
    val os: String?,
    val osVersion: String?,
    val architecture: String?,
    val author: String?,
    val comment: String?,
    val sizeBytes: Long?,
    val graphDriver: DockerImageGraphDriver?,
    val rootFs: DockerImageRootFs?,
    val config: DockerImageConfig?
)

data class DockerImageConfig(
    val env: Map<String, String?>,
    val cmd: List<String>,
    val entrypoint: List<String>,
    val workingDir: String?,
    val user: String?,
    val labels: Map<String, String>,
    val exposedPorts: List<DockerExposedPort>
)

data class DockerImageGraphDriver(
    val name: String?,
    val data: Map<String, String>
)

data class DockerImageRootFs(
    val type: String?,
    val layers: List<String>
)

data class DockerContainerLogsRequest(
    val tail: Int = DEFAULT_TAIL,
    val since: Instant? = null,
    val until: Instant? = null,
    val timestamps: Boolean = false,
    val streams: Set<DockerContainerLogStream> = setOf(
        DockerContainerLogStream.STDOUT,
        DockerContainerLogStream.STDERR
    )
) {
    init {
        if (tail < 0) {
            throw InvalidContainerLogsRequestException("Container logs tail must not be negative")
        }
        if (tail > MAX_TAIL) {
            throw InvalidContainerLogsRequestException(
                "Container logs tail must not be greater than $MAX_TAIL"
            )
        }
        if (streams.isEmpty()) {
            throw InvalidContainerLogsRequestException(
                "At least one container log stream must be requested"
            )
        }
        if (since != null && until != null && since.isAfter(until)) {
            throw InvalidContainerLogsRequestException(
                "Container logs since timestamp must not be after until timestamp"
            )
        }
        if (since != null && !since.isSupportedByDockerLogs()) {
            throw InvalidContainerLogsRequestException(
                "Container logs since timestamp is outside supported Docker range"
            )
        }
        if (until != null && !until.isSupportedByDockerLogs()) {
            throw InvalidContainerLogsRequestException(
                "Container logs until timestamp is outside supported Docker range"
            )
        }
    }

    companion object {
        const val DEFAULT_TAIL = 200
        const val MAX_TAIL = 1_000
    }

    private fun Instant.isSupportedByDockerLogs(): Boolean {
        return epochSecond in Int.MIN_VALUE..Int.MAX_VALUE
    }
}

data class DockerContainerLogs(
    val lines: List<DockerContainerLogLine>
)

data class DockerContainerLogLine(
    val stream: DockerContainerLogStream,
    val line: String
)

enum class DockerContainerLogStream {
    STDOUT,
    STDERR,
    RAW
}

data class DockerContainerSummary(
    val id: String,
    val name: String?,
    val names: List<String>,
    val image: String?,
    val imageId: String?,
    val command: String?,
    val createdEpochSeconds: Long?,
    val state: DockerContainerState,
    val status: String?,
    val ports: List<DockerPortInfo>,
    val labels: Map<String, String>,
    val sizeRwBytes: Long?,
    val sizeRootFsBytes: Long?,
    val networkMode: String?,
    val networks: Map<String, DockerNetworkEndpoint>,
    val mounts: List<DockerMountInfo>
)

data class DockerContainerDetails(
    val id: String,
    val name: String?,
    val image: String?,
    val imageId: String?,
    val createdAt: String?,
    val path: String?,
    val args: List<String>,
    val driver: String?,
    val platform: String?,
    val state: DockerContainerStateDetails,
    val config: DockerContainerConfig,
    val hostConfig: DockerContainerHostConfig,
    val ports: List<DockerPortInfo>,
    val labels: Map<String, String>,
    val env: Map<String, String?>,
    val envNames: List<String>,
    val mounts: List<DockerMountInfo>,
    val networks: Map<String, DockerNetworkEndpoint>,
    val restartPolicy: DockerRestartPolicy,
    val restartCount: Int?,
    val execIds: List<String>,
    val sizeRwBytes: Long?,
    val sizeRootFsBytes: Long?
)

enum class DockerContainerState {
    CREATED,
    RUNNING,
    PAUSED,
    RESTARTING,
    REMOVING,
    EXITED,
    DEAD,
    UNKNOWN
}

data class DockerContainerStateDetails(
    val state: DockerContainerState,
    val statusText: String?,
    val running: Boolean?,
    val paused: Boolean?,
    val restarting: Boolean?,
    val oomKilled: Boolean?,
    val dead: Boolean?,
    val pid: Long?,
    val exitCode: Long?,
    val error: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val health: DockerContainerHealth?
)

data class DockerContainerHealth(
    val status: String?,
    val failingStreak: Int?
)

data class DockerContainerConfig(
    val image: String?,
    val env: Map<String, String?>,
    val envNames: List<String>,
    val cmd: List<String>,
    val entrypoint: List<String>,
    val workingDir: String?,
    val user: String?,
    val hostname: String?,
    val labels: Map<String, String>,
    val exposedPorts: List<DockerExposedPort>,
    val healthcheck: DockerHealthcheck?
)

data class DockerHealthcheck(
    val test: List<String>,
    val intervalNanos: Long?,
    val timeoutNanos: Long?,
    val startPeriodNanos: Long?,
    val retries: Int?
)

data class DockerExposedPort(
    val port: Int,
    val type: String
)

data class DockerContainerHostConfig(
    val networkMode: String?,
    val privileged: Boolean?,
    val readonlyRootfs: Boolean?,
    val autoRemove: Boolean?,
    val publishAllPorts: Boolean?,
    val restartPolicy: DockerRestartPolicy,
    val memoryBytes: Long?,
    val memorySwapBytes: Long?,
    val memoryReservationBytes: Long?,
    val nanoCpus: Long?,
    val cpuShares: Int?,
    val cpuQuota: Long?,
    val cpuPeriod: Long?,
    val cpusetCpus: String?,
    val pidsLimit: Long?,
    val capAdd: List<String>,
    val capDrop: List<String>,
    val securityOptions: List<String>,
    val tmpfs: Map<String, String>,
    val sysctls: Map<String, String>
)

data class DockerRestartPolicy(
    val type: DockerRestartPolicyType,
    val maxRetries: Int? = null
)

enum class DockerRestartPolicyType {
    NO,
    ALWAYS,
    ON_FAILURE,
    UNLESS_STOPPED
}

data class DockerPortInfo(
    val privatePort: Int?,
    val publicPort: Int?,
    val ip: String?,
    val type: String?
)

data class DockerMountInfo(
    val type: String?,
    val name: String?,
    val source: String?,
    val target: String?,
    val driver: String?,
    val mode: String?,
    val readOnly: Boolean?,
    val propagation: String?
)

data class DockerNetworkEndpoint(
    val networkId: String?,
    val endpointId: String?,
    val gateway: String?,
    val ipAddress: String?,
    val ipPrefixLen: Int?,
    val ipv6Gateway: String?,
    val globalIpv6Address: String?,
    val globalIpv6PrefixLen: Int?,
    val aliases: List<String>
)

data class DockerContainerStats(
    val cpuPercent: Double?,
    val memoryUsageBytes: Long?,
    val memoryLimitBytes: Long?,
    val memoryMaxUsageBytes: Long?,
    val memoryFailCount: Long?,
    val memoryCacheBytes: Long?,
    val memoryRssBytes: Long?,
    val memorySwapBytes: Long?,
    val networkRxBytes: Long?,
    val networkTxBytes: Long?,
    val networks: Map<String, DockerNetworkStats>,
    val pidsCurrent: Long?,
    val diskReadBytes: Long?,
    val diskWriteBytes: Long?,
    val readAt: String?,
    val preReadAt: String?
)

data class DockerNetworkStats(
    val rxBytes: Long?,
    val txBytes: Long?
)
