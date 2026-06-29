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
            DockerAgentStatus(
                state = DockerAgentState.UNAVAILABLE,
                errorMessage = exception.safeMessage()
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
            throw DockerAgentUnavailableException("Interrupted while waiting for Docker stats")
        }

        if (!completed) {
            throw DockerAgentUnavailableException("Timed out while waiting for Docker stats")
        }

        return stats ?: throw DockerAgentUnavailableException("Docker did not return stats")
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
            status = stateStatus,
            ports = networkSettings?.ports.toPortInfos(),
            labels = config?.labels.orEmpty(),
            envNames = config?.env.toEnvNames(),
            mounts = mounts.orEmpty().map {
                MountInfo(
                    type = inferMountType(name = it.name, source = it.source),
                    source = it.source,
                    target = it.destination?.path,
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

    private fun inferMountType(name: String?, source: String?): String? {
        return when {
            !name.isNullOrBlank() -> "volume"
            !source.isNullOrBlank() -> "bind"
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

    @Suppress("DEPRECATION")
    private fun Statistics.toContainerStats(): ContainerStats {
        val networkStats = networks ?: network

        return ContainerStats(
            cpuPercent = calculateCpuPercent(this),
            memoryUsageBytes = memoryStats?.usage,
            memoryLimitBytes = memoryStats?.limit,
            networkRxBytes = networkStats.orEmpty().values.sumOf { it.rxBytes ?: 0L },
            networkTxBytes = networkStats.orEmpty().values.sumOf { it.txBytes ?: 0L }
        )
    }

    private fun calculateCpuPercent(stats: Statistics): Double {
        val cpuDelta = (stats.cpuStats?.cpuUsage?.totalUsage ?: return 0.0) -
            (stats.preCpuStats?.cpuUsage?.totalUsage ?: return 0.0)
        val systemDelta = (stats.cpuStats?.systemCpuUsage ?: return 0.0) -
            (stats.preCpuStats?.systemCpuUsage ?: return 0.0)
        val onlineCpus = stats.cpuStats?.onlineCpus
            ?: stats.cpuStats?.cpuUsage?.percpuUsage?.size?.toLong()
            ?: 1L

        if (cpuDelta <= 0L || systemDelta <= 0L) {
            return 0.0
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
            throw DockerAgentUnavailableException("Docker agent call failed: ${exception.safeMessage()}")
        } catch (exception: DockerClientException) {
            throw DockerAgentUnavailableException("Docker agent call failed: ${exception.safeMessage()}")
        }
    }

    private fun RuntimeException.safeMessage(): String {
        return message?.takeIf { it.isNotBlank() } ?: this::class.simpleName.orEmpty()
    }

    private companion object {
        const val STATS_TIMEOUT_SECONDS = 5L
    }
}
