package io.dockistry.platform.docker.infrastructure

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.HealthState
import com.github.dockerjava.api.exception.DockerClientException
import com.github.dockerjava.api.exception.DockerException
import com.github.dockerjava.api.exception.NotFoundException as DockerNotFoundException
import com.github.dockerjava.api.model.BlkioStatEntry
import com.github.dockerjava.api.model.BlkioStatsConfig
import com.github.dockerjava.api.model.Container
import com.github.dockerjava.api.model.ContainerConfig
import com.github.dockerjava.api.model.ContainerMount
import com.github.dockerjava.api.model.ContainerNetwork
import com.github.dockerjava.api.model.ContainerPort
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.HealthCheck
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Ports
import com.github.dockerjava.api.model.StatisticNetworksConfig
import com.github.dockerjava.api.model.Statistics
import com.github.dockerjava.api.model.StreamType
import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerAgentUnavailableException
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.model.DockerAgentAvailability
import io.dockistry.platform.docker.domain.model.DockerAgentInfo
import io.dockistry.platform.docker.domain.model.DockerContainerConfig
import io.dockistry.platform.docker.domain.model.DockerContainerDetails
import io.dockistry.platform.docker.domain.model.DockerContainerFilter
import io.dockistry.platform.docker.domain.model.DockerContainerHealth
import io.dockistry.platform.docker.domain.model.DockerContainerLogLine
import io.dockistry.platform.docker.domain.model.DockerContainerLogStream
import io.dockistry.platform.docker.domain.model.DockerContainerLogs
import io.dockistry.platform.docker.domain.model.DockerContainerLogsRequest
import io.dockistry.platform.docker.domain.model.DockerContainerHostConfig
import io.dockistry.platform.docker.domain.model.DockerContainerState
import io.dockistry.platform.docker.domain.model.DockerContainerStateDetails
import io.dockistry.platform.docker.domain.model.DockerContainerStats
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerExposedPort
import io.dockistry.platform.docker.domain.model.DockerHealthcheck
import io.dockistry.platform.docker.domain.model.DockerMountInfo
import io.dockistry.platform.docker.domain.model.DockerNetworkEndpoint
import io.dockistry.platform.docker.domain.model.DockerNetworkStats
import io.dockistry.platform.docker.domain.model.DockerPortInfo
import io.dockistry.platform.docker.domain.model.DockerRestartPolicy
import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import com.github.dockerjava.api.model.Frame as DockerLogFrame
import com.github.dockerjava.api.command.InspectContainerResponse as DockerInspectContainerResponse
import com.github.dockerjava.api.model.RestartPolicy as DockerJavaRestartPolicy

@Component
class LocalDockerAgentClient(
    private val dockerClient: DockerClient
) : DockerAgentClient {

    override fun status(): DockerAgentInfo {
        return try {
            val version = dockerClient.versionCmd().exec()
            val info = dockerClient.infoCmd().exec()

            DockerAgentInfo(
                state = DockerAgentAvailability.AVAILABLE,
                engineVersion = version.version ?: info.serverVersion,
                apiVersion = version.apiVersion,
                operatingSystem = info.operatingSystem ?: version.operatingSystem,
                architecture = info.architecture ?: version.arch,
                serverName = info.name,
                dockerRootDir = info.dockerRootDir,
                cgroupDriver = info.cGroupDriver,
                cgroupVersion = info.cGroupVersion
            )
        } catch (exception: RuntimeException) {
            dockerLogger.warn("Docker agent status check failed: {}", exception.safeMessage(), exception)
            DockerAgentInfo(
                state = DockerAgentAvailability.UNAVAILABLE,
                errorMessage = exception.safeMessage()
            )
        }
    }

    override fun listContainers(filter: DockerContainerFilter): List<DockerContainerSummary> {
        return dockerCall {
            val command = dockerClient.listContainersCmd()
                .withShowAll(true)

            if (filter.labels.isNotEmpty()) {
                command.withLabelFilter(filter.labels)
            }

            command.exec().map { it.toSummary() }
        }
    }

    override fun inspectContainer(containerId: String): DockerContainerDetails {
        return dockerCall {
            dockerClient.inspectContainerCmd(containerId)
                .exec()
                .toDetails()
        }
    }

    override fun getContainerStats(containerId: String): DockerContainerStats {
        return dockerCall {
            readSingleStats(containerId).toContainerStats()
        }
    }

    override fun getLogs(
        containerId: String,
        request: DockerContainerLogsRequest
    ): DockerContainerLogs {
        return dockerCall {
            readLogs(containerId, request)
        }
    }

    override fun startContainer(containerId: String) {
        dockerCall {
            dockerClient.startContainerCmd(containerId).exec()
        }
    }

    override fun stopContainer(containerId: String) {
        dockerCall {
            dockerClient.stopContainerCmd(containerId).exec()
        }
    }

    override fun restartContainer(containerId: String) {
        dockerCall {
            dockerClient.restartContainerCmd(containerId).exec()
        }
    }

    override fun pauseContainer(containerId: String) {
        dockerCall {
            dockerClient.pauseContainerCmd(containerId).exec()
        }
    }

    override fun unpauseContainer(containerId: String) {
        dockerCall {
            dockerClient.unpauseContainerCmd(containerId).exec()
        }
    }

    override fun deleteContainer(containerId: String) {
        dockerCall {
            dockerClient.removeContainerCmd(containerId)
                .withForce(true)
                .exec()
        }
    }

    private fun readSingleStats(containerId: String): Statistics {
        var stats: Statistics? = null
        /*
         * docker-java exposes stats as an async command because Docker's stats
         * endpoint is stream-first. withNoStream(true) asks Docker for one frame,
         * but the client API still delivers that frame through a callback.
         */
        val callback = object : ResultCallback.Adapter<Statistics>() {
            override fun onNext(item: Statistics) {
                stats = item
            }
        }

        val completed = try {
            dockerClient.statsCmd(containerId)
                .withNoStream(true)
                .exec(callback)
                .awaitCompletion(STATS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            dockerLogger.warn("Docker stats command was interrupted for container {}", containerId, exception)
            throw DockerAgentUnavailableException(DOCKER_AGENT_UNAVAILABLE_MESSAGE)
        }

        if (!completed) {
            dockerLogger.warn("Docker stats command timed out for container {}", containerId)
            throw DockerAgentUnavailableException(DOCKER_AGENT_UNAVAILABLE_MESSAGE)
        }

        return stats ?: run {
            dockerLogger.warn("Docker stats command completed without stats for container {}", containerId)
            throw DockerAgentUnavailableException(DOCKER_AGENT_UNAVAILABLE_MESSAGE)
        }
    }

    private fun readLogs(
        containerId: String,
        request: DockerContainerLogsRequest
    ): DockerContainerLogs {
        val lines = mutableListOf<DockerContainerLogLine>()
        val callback = object : ResultCallback.Adapter<DockerLogFrame>() {
            override fun onNext(item: DockerLogFrame) {
                lines += item.toLogLines()
            }
        }

        val command = dockerClient.logContainerCmd(containerId)
            .withFollowStream(false)
            .withStdOut(DockerContainerLogStream.STDOUT in request.streams)
            .withStdErr(DockerContainerLogStream.STDERR in request.streams)
            .withTimestamps(request.timestamps)
            .withTail(request.tail)

        request.since?.let { command.withSince(it.epochSecond.toInt()) }
        request.until?.let { command.withUntil(it.epochSecond.toInt()) }

        val completed = try {
            command.exec(callback)
                .awaitCompletion(LOGS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            dockerLogger.warn("Docker logs command was interrupted for container {}", containerId, exception)
            throw DockerAgentUnavailableException(DOCKER_AGENT_UNAVAILABLE_MESSAGE)
        }

        if (!completed) {
            dockerLogger.warn("Docker logs command timed out for container {}", containerId)
            throw DockerAgentUnavailableException(DOCKER_AGENT_UNAVAILABLE_MESSAGE)
        }

        return DockerContainerLogs(lines = lines)
    }

    private fun DockerLogFrame.toLogLines(): List<DockerContainerLogLine> {
        return payload
            .decodeToString()
            .trimEnd('\n')
            .split('\n')
            .filter { it.isNotEmpty() }
            .map {
                DockerContainerLogLine(
                    stream = streamType.toLogStream(),
                    line = it.trimEnd('\r')
                )
            }
    }

    private fun Container.toSummary(): DockerContainerSummary {
        val names = names.orEmpty().map { it.trimStart('/') }

        return DockerContainerSummary(
            id = id,
            name = names.firstOrNull(),
            names = names,
            image = image,
            imageId = imageId,
            command = command,
            createdEpochSeconds = created,
            state = state.toContainerState(),
            status = status,
            ports = ports.orEmpty().map { it.toPortInfo() },
            labels = labels.orEmpty(),
            sizeRwBytes = sizeRw,
            sizeRootFsBytes = sizeRootFs,
            networkMode = hostConfig?.networkMode,
            networks = networkSettings?.networks.toNetworkEndpoints(),
            mounts = mounts.orEmpty().map { it.toMountInfo() }
        )
    }

    private fun DockerInspectContainerResponse.toDetails(): DockerContainerDetails {
        val config = config
        val env = config?.env.toEnvMap()
        val mounts = mounts.orEmpty().map { it.toMountInfo() }

        return DockerContainerDetails(
            id = id,
            name = name?.trimStart('/'),
            image = config?.image ?: imageId,
            imageId = imageId,
            createdAt = created,
            path = path,
            args = args.orEmpty().toList(),
            driver = driver,
            platform = platform,
            state = state.toStateDetails(),
            config = config.toContainerConfig(),
            hostConfig = hostConfig.toContainerHostConfig(),
            ports = networkSettings?.ports.toPortInfos(),
            labels = config?.labels.orEmpty(),
            env = env,
            envNames = env.keys.sorted(),
            mounts = mounts,
            networks = networkSettings?.networks.toNetworkEndpoints(),
            restartPolicy = hostConfig?.restartPolicy.toRestartPolicy(),
            restartCount = restartCount,
            execIds = execIds.orEmpty(),
            sizeRwBytes = sizeRw,
            sizeRootFsBytes = sizeRootFs
        )
    }

    private fun ContainerPort.toPortInfo(): DockerPortInfo {
        return DockerPortInfo(
            privatePort = privatePort,
            publicPort = publicPort,
            ip = ip,
            type = type
        )
    }

    private fun Ports?.toPortInfos(): List<DockerPortInfo> {
        return this?.bindings.orEmpty().flatMap { (exposedPort, bindings) ->
            bindings?.map { binding ->
                exposedPort.toPortInfo(binding)
            } ?: listOf(exposedPort.toPortInfo(null))
        }
    }

    private fun ExposedPort.toPortInfo(binding: Ports.Binding?): DockerPortInfo {
        return DockerPortInfo(
            privatePort = port,
            publicPort = binding?.hostPortSpec?.toIntOrNull(),
            ip = binding?.hostIp,
            type = protocol.toString()
        )
    }

    private fun ContainerMount.toMountInfo(): DockerMountInfo {
        return DockerMountInfo(
            type = inferMountType(name = name, source = source, destination = destination),
            name = name,
            source = source,
            target = destination,
            driver = driver,
            mode = mode,
            readOnly = rw?.let { !it },
            propagation = propagation
        )
    }

    private fun DockerInspectContainerResponse.Mount.toMountInfo(): DockerMountInfo {
        val destination = destination?.path

        return DockerMountInfo(
            type = inferMountType(name = name, source = source, destination = destination),
            name = name,
            source = source,
            target = destination,
            driver = driver,
            mode = mode,
            readOnly = rw?.let { !it },
            propagation = null
        )
    }

    private fun Array<String>?.toEnvMap(): Map<String, String?> {
        return this.orEmpty()
            .mapNotNull { item ->
                val separatorIndex = item.indexOf('=')
                val name = if (separatorIndex >= 0) item.substring(0, separatorIndex) else item

                name.takeIf { it.isNotBlank() }?.let {
                    it to if (separatorIndex >= 0) item.substring(separatorIndex + 1) else null
                }
            }
            .toMap()
    }

    private fun ContainerConfig?.toContainerConfig(): DockerContainerConfig {
        val env = this?.env.toEnvMap()

        return DockerContainerConfig(
            image = this?.image,
            env = env,
            envNames = env.keys.sorted(),
            cmd = this?.cmd.orEmpty().toList(),
            entrypoint = this?.entrypoint.orEmpty().toList(),
            workingDir = this?.workingDir,
            user = this?.user,
            hostname = this?.hostName,
            labels = this?.labels.orEmpty(),
            exposedPorts = this?.exposedPorts.orEmpty().map {
                DockerExposedPort(
                    port = it.port,
                    type = it.protocol.toString()
                )
            },
            healthcheck = this?.healthcheck.toHealthcheck()
        )
    }

    private fun HealthCheck?.toHealthcheck(): DockerHealthcheck? {
        return this?.let {
            DockerHealthcheck(
                test = it.test.orEmpty(),
                intervalNanos = it.interval,
                timeoutNanos = it.timeout,
                startPeriodNanos = it.startPeriod,
                retries = it.retries
            )
        }
    }

    private fun HostConfig?.toContainerHostConfig(): DockerContainerHostConfig {
        return DockerContainerHostConfig(
            networkMode = this?.networkMode,
            privileged = this?.privileged,
            readonlyRootfs = this?.readonlyRootfs,
            autoRemove = this?.autoRemove,
            publishAllPorts = this?.publishAllPorts,
            restartPolicy = this?.restartPolicy.toRestartPolicy(),
            memoryBytes = this?.memory,
            memorySwapBytes = this?.memorySwap,
            memoryReservationBytes = this?.memoryReservation,
            nanoCpus = this?.nanoCPUs,
            cpuShares = this?.cpuShares,
            cpuQuota = this?.cpuQuota,
            cpuPeriod = this?.cpuPeriod,
            cpusetCpus = this?.cpusetCpus,
            pidsLimit = this?.pidsLimit,
            capAdd = this?.capAdd.orEmpty().map { it.toString() },
            capDrop = this?.capDrop.orEmpty().map { it.toString() },
            securityOptions = this?.securityOpts.orEmpty(),
            tmpfs = this?.tmpFs.orEmpty(),
            sysctls = this?.sysctls.orEmpty()
        )
    }

    private fun DockerInspectContainerResponse.ContainerState?.toStateDetails(): DockerContainerStateDetails {
        val statusText = this?.status

        return DockerContainerStateDetails(
            state = statusText.toContainerState(),
            statusText = statusText,
            running = this?.running,
            paused = this?.paused,
            restarting = this?.restarting,
            oomKilled = this?.oomKilled,
            dead = this?.dead,
            pid = this?.pidLong,
            exitCode = this?.exitCodeLong,
            error = this?.error,
            startedAt = this?.startedAt,
            finishedAt = this?.finishedAt,
            health = this?.health.toHealth()
        )
    }

    private fun HealthState?.toHealth(): DockerContainerHealth? {
        return this?.let {
            DockerContainerHealth(
                status = it.status,
                failingStreak = it.failingStreak
            )
        }
    }

    private fun Map<String, ContainerNetwork>?.toNetworkEndpoints(): Map<String, DockerNetworkEndpoint> {
        return this.orEmpty().mapValues { (_, network) ->
            DockerNetworkEndpoint(
                networkId = network.networkID,
                endpointId = network.endpointId,
                gateway = network.gateway,
                ipAddress = network.ipAddress,
                ipPrefixLen = network.ipPrefixLen,
                ipv6Gateway = network.ipV6Gateway,
                globalIpv6Address = network.globalIPv6Address,
                globalIpv6PrefixLen = network.globalIPv6PrefixLen,
                aliases = network.aliases.orEmpty()
            )
        }
    }

    /*
     * Docker inspect JSON includes mount Type, but `docker-java` 3.7.1 does not
     * expose it on InspectContainerResponse.Mount. Infer the common cases from
     * fields that are available in that model: named Docker volumes have Name,
     * bind mounts have Source, and tmpfs mounts have only Destination.
     */
    private fun inferMountType(name: String?, source: String?, destination: String?): String? {
        return when {
            !name.isNullOrBlank() -> "volume"
            !source.isNullOrBlank() -> "bind"
            !destination.isNullOrBlank() -> "tmpfs"
            else -> null
        }
    }

    private fun DockerJavaRestartPolicy?.toRestartPolicy(): DockerRestartPolicy {
        val type = when (this?.name?.lowercase().orEmpty()) {
            "", "no" -> DockerRestartPolicyType.NO
            "always" -> DockerRestartPolicyType.ALWAYS
            "on-failure" -> DockerRestartPolicyType.ON_FAILURE
            "unless-stopped" -> DockerRestartPolicyType.UNLESS_STOPPED
            else -> DockerRestartPolicyType.NO
        }

        return DockerRestartPolicy(
            type = type,
            maxRetries = this?.maximumRetryCount
                ?.takeIf { type == DockerRestartPolicyType.ON_FAILURE && it > 0 }
        )
    }

    private fun StreamType?.toLogStream(): DockerContainerLogStream {
        return when (this) {
            StreamType.STDOUT -> DockerContainerLogStream.STDOUT
            StreamType.STDERR -> DockerContainerLogStream.STDERR
            StreamType.RAW -> DockerContainerLogStream.RAW
            else -> DockerContainerLogStream.RAW
        }
    }

    private fun Statistics.toContainerStats(): DockerContainerStats {
        val networkStats = networks.toNetworkStats()
        val diskIo = blkioStats.toDiskIoBytes()

        return DockerContainerStats(
            cpuPercent = calculateCpuPercent(this),
            memoryUsageBytes = memoryStats?.usage,
            memoryLimitBytes = memoryStats?.limit,
            memoryMaxUsageBytes = memoryStats?.maxUsage,
            memoryFailCount = memoryStats?.failcnt,
            memoryCacheBytes = memoryStats?.stats?.cache,
            memoryRssBytes = memoryStats?.stats?.rss,
            memorySwapBytes = memoryStats?.stats?.swap,
            networkRxBytes = networks?.values?.sumOf { it.rxBytes ?: 0L },
            networkTxBytes = networks?.values?.sumOf { it.txBytes ?: 0L },
            networks = networkStats,
            pidsCurrent = pidsStats?.current,
            diskReadBytes = diskIo.readBytes,
            diskWriteBytes = diskIo.writeBytes,
            readAt = read,
            preReadAt = preread
        )
    }

    private fun Map<String, StatisticNetworksConfig>?.toNetworkStats(): Map<String, DockerNetworkStats> {
        return this.orEmpty().mapValues { (_, stats) ->
            DockerNetworkStats(
                rxBytes = stats.rxBytes,
                txBytes = stats.txBytes
            )
        }
    }

    private fun BlkioStatsConfig?.toDiskIoBytes(): DiskIoBytes {
        val entries = this?.ioServiceBytesRecursive.orEmpty()

        return DiskIoBytes(
            readBytes = entries.sumOperation("Read"),
            writeBytes = entries.sumOperation("Write")
        )
    }

    private fun List<BlkioStatEntry>.sumOperation(operation: String): Long? {
        val matchingEntries = filter { it.op.equals(operation, ignoreCase = true) }

        return matchingEntries
            .takeIf { it.isNotEmpty() }
            ?.sumOf { it.value ?: 0L }
    }

    /*
     * Docker reports CPU usage as cumulative counters. cpu_stats is the current
     * sample, precpu_stats is the previous one, so the useful value is the delta
     * between them. The final multiplication by online CPUs matches Docker CLI
     * semantics: a container using one full core on a 4-core host is ~100%, and a
     * container using two full cores can report ~200%.
     */
    private fun calculateCpuPercent(stats: Statistics): Double? {
        val cpuDelta = (stats.cpuStats?.cpuUsage?.totalUsage ?: return null) -
            (stats.preCpuStats?.cpuUsage?.totalUsage ?: return null)
        val systemDelta = (stats.cpuStats?.systemCpuUsage ?: return null) -
            (stats.preCpuStats?.systemCpuUsage ?: return null)
        // Older Docker daemons may not provide online_cpus; percpu_usage is the
        // best available fallback for host CPU count.
        val onlineCpus = stats.cpuStats?.onlineCpus
            ?: stats.cpuStats?.cpuUsage?.percpuUsage?.size?.toLong()
            ?: 1L

        // No positive movement means Docker returned an incomplete or stale
        // stats frame, so there is no meaningful percentage to calculate.
        if (cpuDelta <= 0L || systemDelta <= 0L) {
            return null
        }

        return (cpuDelta.toDouble() / systemDelta.toDouble()) * onlineCpus * 100.0
    }

    private fun String?.toContainerState(): DockerContainerState {
        return when (this?.lowercase()) {
            "created" -> DockerContainerState.CREATED
            "running" -> DockerContainerState.RUNNING
            "paused" -> DockerContainerState.PAUSED
            "restarting" -> DockerContainerState.RESTARTING
            "removing" -> DockerContainerState.REMOVING
            "exited" -> DockerContainerState.EXITED
            "dead" -> DockerContainerState.DEAD
            else -> DockerContainerState.UNKNOWN
        }
    }

    private inline fun <T> dockerCall(operation: () -> T): T {
        return try {
            operation()
        } catch (exception: DockerNotFoundException) {
            throw DockerContainerNotFoundException("Docker container couldn't be found")
        } catch (exception: DockerException) {
            logDockerCallFailure(exception)
            throw DockerAgentUnavailableException(DOCKER_AGENT_UNAVAILABLE_MESSAGE)
        } catch (exception: DockerClientException) {
            logDockerCallFailure(exception)
            throw DockerAgentUnavailableException(DOCKER_AGENT_UNAVAILABLE_MESSAGE)
        }
    }

    private fun logDockerCallFailure(exception: RuntimeException) {
        dockerLogger.warn("Docker agent call failed: {}", exception.safeMessage(), exception)
    }

    private fun RuntimeException.safeMessage(): String {
        return message?.takeIf { it.isNotBlank() } ?: this::class.simpleName.orEmpty()
    }

    private companion object {
        val dockerLogger: Logger = LoggerFactory.getLogger(LocalDockerAgentClient::class.java)

        const val DOCKER_AGENT_UNAVAILABLE_MESSAGE = "Docker agent is unavailable"
        const val STATS_TIMEOUT_SECONDS = 5L
        const val LOGS_TIMEOUT_SECONDS = 10L
    }

    private data class DiskIoBytes(
        val readBytes: Long?,
        val writeBytes: Long?
    )
}
