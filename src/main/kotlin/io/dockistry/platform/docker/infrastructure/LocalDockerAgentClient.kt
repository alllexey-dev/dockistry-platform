package io.dockistry.platform.docker.infrastructure

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.exception.DockerClientException
import com.github.dockerjava.api.exception.DockerException
import com.github.dockerjava.api.exception.NotFoundException as DockerNotFoundException
import com.github.dockerjava.api.model.Container
import com.github.dockerjava.api.model.ContainerPort
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.Ports
import com.github.dockerjava.api.model.Statistics
import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerAgentUnavailableException
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.dto.ContainerDetails
import io.dockistry.platform.docker.domain.dto.ContainerFilter
import io.dockistry.platform.docker.domain.dto.ContainerState
import io.dockistry.platform.docker.domain.dto.ContainerStats
import io.dockistry.platform.docker.domain.dto.ContainerSummary
import io.dockistry.platform.docker.domain.dto.DockerAgentState
import io.dockistry.platform.docker.domain.dto.DockerAgentStatus
import io.dockistry.platform.docker.domain.dto.MountInfo
import io.dockistry.platform.docker.domain.dto.PortInfo
import io.dockistry.platform.docker.domain.dto.RestartPolicy
import io.dockistry.platform.docker.domain.dto.RestartPolicyType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import com.github.dockerjava.api.command.InspectContainerResponse as DockerInspectContainerResponse
import com.github.dockerjava.api.model.RestartPolicy as DockerRestartPolicy

@Component
class LocalDockerAgentClient(
    private val dockerClient: DockerClient
) : DockerAgentClient {

    override fun status(): DockerAgentStatus {
        return try {
            val version = dockerClient.versionCmd().exec()
            val info = dockerClient.infoCmd().exec()

            DockerAgentStatus(
                state = DockerAgentState.AVAILABLE,
                engineVersion = version.version ?: info.serverVersion,
                apiVersion = version.apiVersion,
                operatingSystem = info.operatingSystem ?: version.operatingSystem
            )
        } catch (exception: RuntimeException) {
            dockerLogger.warn("Docker agent status check failed: {}", exception.safeMessage(), exception)
            DockerAgentStatus(
                state = DockerAgentState.UNAVAILABLE,
                errorMessage = DOCKER_AGENT_UNAVAILABLE_MESSAGE
            )
        }
    }

    override fun listContainers(filter: ContainerFilter): List<ContainerSummary> {
        return dockerCall {
            val command = dockerClient.listContainersCmd()
                .withShowAll(true)

            if (filter.labels.isNotEmpty()) {
                command.withLabelFilter(filter.labels)
            }

            command.exec().map { it.toSummary() }
        }
    }

    override fun inspectContainer(containerId: String): ContainerDetails {
        return dockerCall {
            dockerClient.inspectContainerCmd(containerId)
                .exec()
                .toDetails()
        }
    }

    override fun getContainerStats(containerId: String): ContainerStats {
        return dockerCall {
            readSingleStats(containerId).toContainerStats()
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

    private fun Container.toSummary(): ContainerSummary {
        return ContainerSummary(
            id = id,
            name = names?.firstOrNull()?.trimStart('/'),
            image = image,
            state = state.toContainerState(),
            status = status,
            ports = ports.orEmpty().map { it.toPortInfo() },
            labels = labels.orEmpty()
        )
    }

    private fun DockerInspectContainerResponse.toDetails(): ContainerDetails {
        val config = config
        val stateStatus = state?.status

        return ContainerDetails(
            id = id,
            name = name?.trimStart('/'),
            image = config?.image ?: imageId,
            state = stateStatus.toContainerState(),
            ports = networkSettings?.ports.toPortInfos(),
            labels = config?.labels.orEmpty(),
            envNames = config?.env.toEnvNames(),
            mounts = mounts.orEmpty().map {
                val destination = it.destination?.path

                MountInfo(
                    type = inferMountType(
                        name = it.name,
                        source = it.source,
                        destination = destination
                    ),
                    source = it.source,
                    target = destination,
                    readOnly = it.rw?.let { rw -> !rw }
                )
            },
            restartPolicy = hostConfig?.restartPolicy.toRestartPolicy()
        )
    }

    private fun ContainerPort.toPortInfo(): PortInfo {
        return PortInfo(
            privatePort = privatePort,
            publicPort = publicPort,
            ip = ip,
            type = type
        )
    }

    private fun Ports?.toPortInfos(): List<PortInfo> {
        return this?.bindings.orEmpty().flatMap { (exposedPort, bindings) ->
            bindings?.map { binding ->
                exposedPort.toPortInfo(binding)
            } ?: listOf(exposedPort.toPortInfo(null))
        }
    }

    private fun ExposedPort.toPortInfo(binding: Ports.Binding?): PortInfo {
        return PortInfo(
            privatePort = port,
            publicPort = binding?.hostPortSpec?.toIntOrNull(),
            ip = binding?.hostIp,
            type = protocol.toString()
        )
    }

    private fun Array<String>?.toEnvNames(): List<String> {
        return this.orEmpty()
            .mapNotNull { env ->
                env.substringBefore("=").takeIf { it.isNotBlank() }
            }
            .sorted()
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

    private fun DockerRestartPolicy?.toRestartPolicy(): RestartPolicy {
        val type = when (this?.name?.lowercase().orEmpty()) {
            "", "no" -> RestartPolicyType.NO
            "always" -> RestartPolicyType.ALWAYS
            "on-failure" -> RestartPolicyType.ON_FAILURE
            "unless-stopped" -> RestartPolicyType.UNLESS_STOPPED
            else -> RestartPolicyType.NO
        }

        return RestartPolicy(
            type = type,
            maxRetries = this?.maximumRetryCount
                ?.takeIf { type == RestartPolicyType.ON_FAILURE && it > 0 }
        )
    }

    private fun Statistics.toContainerStats(): ContainerStats {
        return ContainerStats(
            cpuPercent = calculateCpuPercent(this),
            memoryUsageBytes = memoryStats?.usage,
            memoryLimitBytes = memoryStats?.limit,
            networkRxBytes = networks?.values?.sumOf { it.rxBytes ?: 0L },
            networkTxBytes = networks?.values?.sumOf { it.txBytes ?: 0L }
        )
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

    private fun String?.toContainerState(): ContainerState {
        return when (this?.lowercase()) {
            "created" -> ContainerState.CREATED
            "running" -> ContainerState.RUNNING
            "paused" -> ContainerState.PAUSED
            "restarting" -> ContainerState.RESTARTING
            "removing" -> ContainerState.REMOVING
            "exited" -> ContainerState.EXITED
            "dead" -> ContainerState.DEAD
            else -> ContainerState.UNKNOWN
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
    }
}
