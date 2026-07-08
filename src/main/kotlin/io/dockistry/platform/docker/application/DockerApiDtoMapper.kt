package io.dockistry.platform.docker.application

import io.dockistry.platform.docker.domain.dto.ContainerDetails
import io.dockistry.platform.docker.domain.dto.ContainerLogLine
import io.dockistry.platform.docker.domain.dto.ContainerLogStream
import io.dockistry.platform.docker.domain.dto.ContainerLogs
import io.dockistry.platform.docker.domain.dto.ContainerState
import io.dockistry.platform.docker.domain.dto.ContainerStats
import io.dockistry.platform.docker.domain.dto.ContainerSummary
import io.dockistry.platform.docker.domain.dto.DockerAgentState
import io.dockistry.platform.docker.domain.dto.DockerAgentStatus
import io.dockistry.platform.docker.domain.dto.ExposedPortInfo
import io.dockistry.platform.docker.domain.dto.ImageConfig
import io.dockistry.platform.docker.domain.dto.ImageInfo
import io.dockistry.platform.docker.domain.dto.ImageReference
import io.dockistry.platform.docker.domain.dto.ImageRegistryInfo
import io.dockistry.platform.docker.domain.dto.ImageRootFs
import io.dockistry.platform.docker.domain.dto.ImageSummary
import io.dockistry.platform.docker.domain.dto.MountInfo
import io.dockistry.platform.docker.domain.dto.PortInfo
import io.dockistry.platform.docker.domain.dto.RestartPolicy
import io.dockistry.platform.docker.domain.dto.RestartPolicyType
import io.dockistry.platform.docker.domain.model.DockerAgentAvailability
import io.dockistry.platform.docker.domain.model.DockerAgentInfo
import io.dockistry.platform.docker.domain.model.DockerContainerDetails
import io.dockistry.platform.docker.domain.model.DockerContainerLogStream
import io.dockistry.platform.docker.domain.model.DockerContainerLogs
import io.dockistry.platform.docker.domain.model.DockerContainerState
import io.dockistry.platform.docker.domain.model.DockerContainerStats
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerExposedPort
import io.dockistry.platform.docker.domain.model.DockerImageConfig
import io.dockistry.platform.docker.domain.model.DockerImageGraphDriver
import io.dockistry.platform.docker.domain.model.DockerImageInfo
import io.dockistry.platform.docker.domain.model.DockerImageReference
import io.dockistry.platform.docker.domain.model.DockerImageRegistryInfo
import io.dockistry.platform.docker.domain.model.DockerImageRootFs
import io.dockistry.platform.docker.domain.model.DockerImageSummary
import io.dockistry.platform.docker.domain.model.DockerMountInfo
import io.dockistry.platform.docker.domain.model.DockerPortInfo
import io.dockistry.platform.docker.domain.model.DockerRestartPolicy
import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType
import org.springframework.stereotype.Component

@Component
class DockerApiDtoMapper {
    fun toStatusDto(info: DockerAgentInfo): DockerAgentStatus {
        return DockerAgentStatus(
            state = info.state.toDto(),
            engineVersion = info.engineVersion,
            apiVersion = info.apiVersion,
            operatingSystem = info.operatingSystem,
            errorMessage = if (info.state == DockerAgentAvailability.UNAVAILABLE) {
                DOCKER_AGENT_UNAVAILABLE_MESSAGE
            } else {
                null
            }
        )
    }

    fun toImageSummaryDtos(images: List<DockerImageSummary>): List<ImageSummary> {
        return images.map { it.toDto() }
    }

    fun toImageInfoDto(image: DockerImageInfo): ImageInfo {
        return ImageInfo(
            id = image.id,
            repoTags = image.repoTags.map { it.toDto() },
            repoDigests = image.repoDigests,
            createdAt = image.createdAt,
            dockerVersion = image.dockerVersion,
            os = image.os,
            osVersion = image.osVersion,
            architecture = image.architecture,
            author = image.author,
            comment = image.comment,
            sizeBytes = image.sizeBytes,
            rootFs = image.rootFs?.toDto(),
            config = image.config?.toDto()
        )
    }

    fun toSummaryDtos(containers: List<DockerContainerSummary>): List<ContainerSummary> {
        return containers.map { toSummaryDto(it) }
    }

    fun toSummaryDto(container: DockerContainerSummary): ContainerSummary {
        return ContainerSummary(
            id = container.id,
            name = container.name,
            image = container.image,
            state = container.state.toDto(),
            status = container.status,
            ports = container.ports.map { it.toDto() },
            labels = container.labels
        )
    }

    fun toDetailsDto(container: DockerContainerDetails): ContainerDetails {
        return ContainerDetails(
            id = container.id,
            name = container.name,
            image = container.image,
            state = container.state.state.toDto(),
            ports = container.ports.map { it.toDto() },
            labels = container.labels,
            envNames = container.envNames,
            mounts = container.mounts.map { it.toDto() },
            restartPolicy = container.restartPolicy.toDto()
        )
    }

    fun toStatsDto(stats: DockerContainerStats): ContainerStats {
        return ContainerStats(
            cpuPercent = stats.cpuPercent,
            memoryUsageBytes = stats.memoryUsageBytes,
            memoryLimitBytes = stats.memoryLimitBytes,
            networkRxBytes = stats.networkRxBytes,
            networkTxBytes = stats.networkTxBytes,
            diskReadBytes = stats.diskReadBytes,
            diskWriteBytes = stats.diskWriteBytes
        )
    }

    fun toLogsDto(logs: DockerContainerLogs): ContainerLogs {
        return ContainerLogs(
            lines = logs.lines.map {
                ContainerLogLine(
                    stream = it.stream.toDto(),
                    line = it.line
                )
            }
        )
    }

    private fun DockerImageSummary.toDto(): ImageSummary {
        return ImageSummary(
            id = id,
            references = references.map { it.toDto() },
            repoDigests = repoDigests,
            createdEpochSeconds = createdEpochSeconds,
            sizeBytes = sizeBytes,
            labels = labels
        )
    }

    private fun DockerImageReference.toDto(): ImageReference {
        return ImageReference(
            raw = raw,
            registry = registry?.toDto(),
            namespace = namespace,
            repository = repository,
            tag = tag
        )
    }

    private fun DockerImageRegistryInfo.toDto(): ImageRegistryInfo {
        return ImageRegistryInfo(
            address = address,
            port = port
        )
    }

    private fun DockerImageConfig.toDto(): ImageConfig {
        return ImageConfig(
            envNames = env.keys.sorted(),
            cmd = cmd,
            entrypoint = entrypoint,
            workingDir = workingDir,
            user = user,
            labels = labels,
            exposedPorts = exposedPorts.map { it.toDto() }
        )
    }

    private fun DockerExposedPort.toDto(): ExposedPortInfo {
        return ExposedPortInfo(
            port = port,
            type = type
        )
    }

    private fun DockerImageRootFs.toDto(): ImageRootFs {
        return ImageRootFs(
            type = type,
            layers = layers
        )
    }

    private fun DockerAgentAvailability.toDto(): DockerAgentState {
        return when (this) {
            DockerAgentAvailability.AVAILABLE -> DockerAgentState.AVAILABLE
            DockerAgentAvailability.UNAVAILABLE -> DockerAgentState.UNAVAILABLE
        }
    }

    private fun DockerContainerState.toDto(): ContainerState {
        return when (this) {
            DockerContainerState.CREATED -> ContainerState.CREATED
            DockerContainerState.RUNNING -> ContainerState.RUNNING
            DockerContainerState.PAUSED -> ContainerState.PAUSED
            DockerContainerState.RESTARTING -> ContainerState.RESTARTING
            DockerContainerState.REMOVING -> ContainerState.REMOVING
            DockerContainerState.EXITED -> ContainerState.EXITED
            DockerContainerState.DEAD -> ContainerState.DEAD
            DockerContainerState.UNKNOWN -> ContainerState.UNKNOWN
        }
    }

    private fun DockerPortInfo.toDto(): PortInfo {
        return PortInfo(
            privatePort = privatePort,
            publicPort = publicPort,
            ip = ip,
            type = type
        )
    }

    private fun DockerMountInfo.toDto(): MountInfo {
        return MountInfo(
            type = type,
            source = source,
            target = target,
            readOnly = readOnly
        )
    }

    private fun DockerRestartPolicy.toDto(): RestartPolicy {
        return RestartPolicy(
            type = type.toDto(),
            maxRetries = maxRetries
        )
    }

    private fun DockerRestartPolicyType.toDto(): RestartPolicyType {
        return when (this) {
            DockerRestartPolicyType.NO -> RestartPolicyType.NO
            DockerRestartPolicyType.ALWAYS -> RestartPolicyType.ALWAYS
            DockerRestartPolicyType.ON_FAILURE -> RestartPolicyType.ON_FAILURE
            DockerRestartPolicyType.UNLESS_STOPPED -> RestartPolicyType.UNLESS_STOPPED
        }
    }

    private fun DockerContainerLogStream.toDto(): ContainerLogStream {
        return when (this) {
            DockerContainerLogStream.STDOUT -> ContainerLogStream.STDOUT
            DockerContainerLogStream.STDERR -> ContainerLogStream.STDERR
            DockerContainerLogStream.RAW -> ContainerLogStream.RAW
        }
    }

    private companion object {
        const val DOCKER_AGENT_UNAVAILABLE_MESSAGE = "Docker agent is unavailable"
    }
}
