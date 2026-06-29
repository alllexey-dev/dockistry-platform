package io.dockistry.platform.docker.domain.model

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

data class DockerContainerFilter(
    val labels: Map<String, String> = emptyMap()
)

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
    val storage: DockerStorageInfo?,
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
    val failingStreak: Int?,
    val log: List<DockerContainerHealthLog>
)

data class DockerContainerHealthLog(
    val start: String?,
    val end: String?,
    val exitCode: Long?,
    val output: String?
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
    val domainName: String?,
    val labels: Map<String, String>,
    val exposedPorts: List<DockerExposedPort>,
    val attachStdin: Boolean?,
    val attachStdout: Boolean?,
    val attachStderr: Boolean?,
    val stdinOpen: Boolean?,
    val stdinOnce: Boolean?,
    val tty: Boolean?,
    val networkDisabled: Boolean?,
    val macAddress: String?,
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
    val pidMode: String?,
    val ipcMode: String?,
    val cgroupMode: String?,
    val cgroupNamespaceMode: String?,
    val privileged: Boolean?,
    val readonlyRootfs: Boolean?,
    val autoRemove: Boolean?,
    val publishAllPorts: Boolean?,
    val restartPolicy: DockerRestartPolicy,
    val memoryBytes: Long?,
    val memorySwapBytes: Long?,
    val memoryReservationBytes: Long?,
    val memorySwappiness: Long?,
    val nanoCpus: Long?,
    val cpuShares: Int?,
    val cpuQuota: Long?,
    val cpuPeriod: Long?,
    val cpusetCpus: String?,
    val cpusetMems: String?,
    val pidsLimit: Long?,
    val shmSizeBytes: Long?,
    val runtime: String?,
    val capAdd: List<String>,
    val capDrop: List<String>,
    val securityOptions: List<String>,
    val dns: List<String>,
    val dnsSearch: List<String>,
    val dnsOptions: List<String>,
    val extraHosts: List<String>,
    val groupAdd: List<String>,
    val tmpfs: Map<String, String>,
    val sysctls: Map<String, String>,
    val storageOptions: Map<String, String>,
    val logType: String?,
    val logConfig: Map<String, String>
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
    val macAddress: String?,
    val aliases: List<String>
)

data class DockerStorageInfo(
    val driverName: String?,
    val rootDir: String?,
    val deviceId: String?,
    val deviceName: String?,
    val deviceSize: String?,
    val dir: String?
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
    val blockIo: DockerBlockIoStats?,
    val readAt: String?,
    val preReadAt: String?
)

data class DockerNetworkStats(
    val rxBytes: Long?,
    val rxDropped: Long?,
    val rxErrors: Long?,
    val rxPackets: Long?,
    val txBytes: Long?,
    val txDropped: Long?,
    val txErrors: Long?,
    val txPackets: Long?
)

data class DockerBlockIoStats(
    val serviceBytes: List<DockerBlockIoEntry>,
    val serviced: List<DockerBlockIoEntry>,
    val queued: List<DockerBlockIoEntry>,
    val serviceTime: List<DockerBlockIoEntry>,
    val waitTime: List<DockerBlockIoEntry>,
    val merged: List<DockerBlockIoEntry>,
    val time: List<DockerBlockIoEntry>,
    val sectors: List<DockerBlockIoEntry>
)

data class DockerBlockIoEntry(
    val major: Long?,
    val minor: Long?,
    val operation: String?,
    val value: Long?
)
