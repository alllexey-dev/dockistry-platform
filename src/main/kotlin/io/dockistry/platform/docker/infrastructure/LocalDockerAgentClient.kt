package io.dockistry.platform.docker.infrastructure

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.HealthState
import com.github.dockerjava.api.exception.DockerClientException
import com.github.dockerjava.api.exception.DockerException
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
import com.github.dockerjava.api.model.Image as DockerJavaImage
import com.github.dockerjava.api.model.Mount
import com.github.dockerjava.api.model.MountType
import com.github.dockerjava.api.model.Ports
import com.github.dockerjava.api.model.StatisticNetworksConfig
import com.github.dockerjava.api.model.Statistics
import com.github.dockerjava.api.model.StreamType
import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerAgentUnavailableException
import io.dockistry.platform.docker.domain.DockerContainerAlreadyPausedException
import io.dockistry.platform.docker.domain.DockerContainerAlreadyStartedException
import io.dockistry.platform.docker.domain.DockerContainerAlreadyStoppedException
import io.dockistry.platform.docker.domain.DockerContainerCreateDependencyNotFoundException
import io.dockistry.platform.docker.domain.DockerContainerCreateNameConflictException
import io.dockistry.platform.docker.domain.DockerContainerCreateRejectedException
import io.dockistry.platform.docker.domain.DockerContainerLifecycleConflictException
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.DockerContainerNotPausedException
import io.dockistry.platform.docker.domain.DockerContainerNotRunningException
import io.dockistry.platform.docker.domain.DockerContainerRemoveConflictException
import io.dockistry.platform.docker.domain.DockerContainerRuntimeResourceConflictException
import io.dockistry.platform.docker.domain.DockerImageNotFoundException
import io.dockistry.platform.docker.domain.DockerImagePullAccessDeniedException
import io.dockistry.platform.docker.domain.DockerImagePullFailedException
import io.dockistry.platform.docker.domain.DockerImagePullTimeoutException
import io.dockistry.platform.docker.domain.DockerImageRegistryUnavailableException
import io.dockistry.platform.docker.domain.DockerImageRemoveConflictException
import io.dockistry.platform.docker.domain.DockerNetworkCreateDependencyNotFoundException
import io.dockistry.platform.docker.domain.DockerNetworkCreateNameConflictException
import io.dockistry.platform.docker.domain.DockerNetworkCreateRejectedException
import io.dockistry.platform.docker.domain.DockerNetworkNameAmbiguityException
import io.dockistry.platform.docker.domain.DockerNetworkNotFoundException
import io.dockistry.platform.docker.domain.DockerNetworkRemoveConflictException
import io.dockistry.platform.docker.domain.DockerOperationFailedException
import io.dockistry.platform.docker.domain.DockerVolumeCreateDependencyNotFoundException
import io.dockistry.platform.docker.domain.DockerVolumeCreateNameConflictException
import io.dockistry.platform.docker.domain.DockerVolumeCreateRejectedException
import io.dockistry.platform.docker.domain.DockerVolumeNotFoundException
import io.dockistry.platform.docker.domain.DockerVolumeRemoveConflictException
import io.dockistry.platform.docker.domain.model.DockerAgentAvailability
import io.dockistry.platform.docker.domain.model.DockerAgentInfo
import io.dockistry.platform.docker.domain.model.DockerContainerConfig
import io.dockistry.platform.docker.domain.model.DockerContainerCreateCommand
import io.dockistry.platform.docker.domain.model.DockerContainerDetails
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
import io.dockistry.platform.docker.domain.model.DockerImageConfig
import io.dockistry.platform.docker.domain.model.DockerImageGraphDriver
import io.dockistry.platform.docker.domain.model.DockerImageInfo
import io.dockistry.platform.docker.domain.model.DockerImageRef
import io.dockistry.platform.docker.domain.model.DockerImageReference
import io.dockistry.platform.docker.domain.model.DockerImageRegistryInfo
import io.dockistry.platform.docker.domain.model.DockerImageRootFs
import io.dockistry.platform.docker.domain.model.DockerImageSummary
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.docker.domain.model.DockerMountInfo
import io.dockistry.platform.docker.domain.model.DockerNetworkEndpoint
import io.dockistry.platform.docker.domain.model.DockerNetworkRef
import io.dockistry.platform.docker.domain.model.DockerNetworkStats
import io.dockistry.platform.docker.domain.model.DockerPortInfo
import io.dockistry.platform.docker.domain.model.DockerPortProtocol
import io.dockistry.platform.docker.domain.model.DockerRestartPolicy
import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType
import io.dockistry.platform.docker.domain.model.DockerVolumeRef
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.github.dockerjava.api.model.Frame as DockerLogFrame
import com.github.dockerjava.api.command.GraphData as DockerJavaGraphData
import com.github.dockerjava.api.command.GraphDriver as DockerJavaGraphDriver
import com.github.dockerjava.api.command.InspectContainerResponse as DockerInspectContainerResponse
import com.github.dockerjava.api.command.InspectImageResponse as DockerInspectImageResponse
import com.github.dockerjava.api.command.RootFS as DockerJavaRootFs
import com.github.dockerjava.api.model.RestartPolicy as DockerJavaRestartPolicy

@Component
class LocalDockerAgentClient(
    private val dockerClient: DockerClient,
    private val dockerAgentProperties: DockerAgentProperties
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

    override fun listImages(): List<DockerImageSummary> {
        return dockerOperationCall {
            dockerClient.listImagesCmd()
                .withShowAll(true)
                .exec()
                .map { it.toImageSummary() }
        }
    }

    override fun inspectImage(imageId: String): DockerImageInfo {
        return dockerOperationCall(
            responseMapper = { response ->
                if (response.statusCode == HTTP_NOT_FOUND) {
                    DockerImageNotFoundException("Docker image couldn't be found")
                } else {
                    null
                }
            }
        ) {
            dockerClient.inspectImageCmd(imageId)
                .exec()
                .toImageInfo()
        }
    }

    override fun pullImage(imageRef: DockerImageRef) {
        val callback = DockerImagePullResultCallback()

        try {
            val completed = dockerHttpCall {
                dockerClient.pullImageCmd(imageRef.repository)
                    .withTag(imageRef.tag)
                    .exec(callback)
                    .awaitCompletion(
                        dockerAgentProperties.imagePullTimeout,
                        TimeUnit.MILLISECONDS
                    )
            }

            if (!completed) {
                dockerLogger.warn(
                    "Docker image pull timed out for {}:{} after {} ms",
                    imageRef.repository,
                    imageRef.tag,
                    dockerAgentProperties.imagePullTimeout
                )
                throw DockerImagePullTimeoutException()
            }

            callback.failure?.let { failure ->
                throw mapImagePullFailure(failure)
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } catch (exception: DockerHttpResponseException) {
            throw mapImagePullHttpFailure(exception)
        } catch (exception: DockerClientException) {
            val failure = callback.failure
            if (failure != null) {
                throw mapImagePullFailure(failure, exception)
            }

            if (exception.hasIoCause()) {
                throw exception.toAgentUnavailable()
            }

            throw DockerImagePullFailedException(exception.safeMessage())
                .causedBy(exception)
        } catch (exception: RuntimeException) {
            if (exception.hasIoCause()) {
                throw exception.toAgentUnavailable()
            }

            throw exception
        }
    }

    override fun removeImage(imageId: String, force: Boolean) {
        dockerOperationCall(
            responseMapper = { response ->
                when (response.statusCode) {
                    HTTP_CONFLICT -> DockerImageRemoveConflictException(
                        response.messageOr(DOCKER_IMAGE_REMOVE_CONFLICT_MESSAGE)
                    )
                    HTTP_NOT_FOUND ->
                        DockerImageNotFoundException("Docker image couldn't be found")
                    else -> null
                }
            }
        ) {
            dockerClient.removeImageCmd(imageId)
                .withForce(force)
                .exec()
        }
    }

    override fun findNetworkByName(name: String): DockerNetworkRef? {
        return dockerOperationCall {
            val networks = dockerClient.listNetworksCmd()
                .withNameFilter(name)
                .exec()
                .filter { network -> network.name == name }

            if (networks.size > 1) {
                throw DockerNetworkNameAmbiguityException(name)
            }

            networks.singleOrNull()?.let { network ->
                DockerNetworkRef(
                    id = network.id,
                    name = network.name,
                    labels = network.labels.orEmpty()
                )
            }
        }
    }

    override fun createNetwork(
        name: String,
        labels: Map<String, String>
    ): DockerNetworkRef {
        return dockerOperationCall(
            responseMapper = { response ->
                when (response.statusCode) {
                    HTTP_BAD_REQUEST,
                    HTTP_FORBIDDEN,
                    HTTP_NOT_ACCEPTABLE ->
                        DockerNetworkCreateRejectedException(
                            response.messageOr("Docker network creation was rejected")
                        )
                    HTTP_NOT_FOUND ->
                        DockerNetworkCreateDependencyNotFoundException(
                            response.messageOr(
                                "Docker network create dependency couldn't be found"
                            )
                        )
                    HTTP_CONFLICT ->
                        DockerNetworkCreateNameConflictException(
                            response.messageOr("Docker network name is already in use")
                        )
                    else -> null
                }
            }
        ) {
            val response = dockerClient.createNetworkCmd()
                .withName(name)
                .withLabels(labels)
                .exec()

            DockerNetworkRef(
                id = response.id,
                name = name,
                labels = labels
            )
        }
    }

    override fun removeNetwork(networkId: String) {
        dockerOperationCall(
            responseMapper = { response ->
                when (response.statusCode) {
                    HTTP_NOT_FOUND ->
                        DockerNetworkNotFoundException("Docker network couldn't be found")
                    HTTP_FORBIDDEN,
                    HTTP_CONFLICT ->
                        DockerNetworkRemoveConflictException(
                            response.messageOr("Docker network cannot be removed")
                        )
                    else -> null
                }
            }
        ) {
            dockerClient.removeNetworkCmd(networkId).exec()
        }
    }

    override fun createVolume(
        name: String,
        labels: Map<String, String>
    ) {
        dockerOperationCall(
            responseMapper = { response ->
                when (response.statusCode) {
                    HTTP_BAD_REQUEST,
                    HTTP_FORBIDDEN,
                    HTTP_NOT_ACCEPTABLE ->
                        DockerVolumeCreateRejectedException(
                            response.messageOr("Docker volume creation was rejected")
                        )
                    HTTP_NOT_FOUND ->
                        DockerVolumeCreateDependencyNotFoundException(
                            response.messageOr(
                                "Docker volume create dependency couldn't be found"
                            )
                        )
                    HTTP_CONFLICT ->
                        DockerVolumeCreateNameConflictException(
                            response.messageOr("Docker volume name is already in use")
                        )
                    else -> null
                }
            }
        ) {
            dockerClient.createVolumeCmd().withName(name).withLabels(labels).exec()
        }
    }

    override fun findVolumeByName(name: String): DockerVolumeRef? {
        return dockerOperationCall {
            dockerClient.listVolumesCmd()
                .withFilter("name", listOf(name))
                .exec()
                .volumes
                .orEmpty()
                .firstOrNull { volume -> volume.name == name }
                ?.let { volume ->
                    DockerVolumeRef(
                        name = volume.name,
                        labels = volume.labels.orEmpty()
                    )
                }
        }
    }

    override fun listVolumes(filter: DockerLabelsFilter): List<DockerVolumeRef> {
        return dockerOperationCall {
            val command = dockerClient.listVolumesCmd()
            if (filter.labels.isNotEmpty()) {
                command.withFilter(
                    "label",
                    filter.labels.map { (name, value) -> "$name=$value" }
                )
            }

            command.exec().volumes.orEmpty().map { volume ->
                DockerVolumeRef(
                    name = volume.name,
                    labels = volume.labels.orEmpty()
                )
            }
        }
    }

    override fun removeVolume(name: String) {
        dockerOperationCall(
            responseMapper = { response ->
                when (response.statusCode) {
                    HTTP_NOT_FOUND ->
                        DockerVolumeNotFoundException("Docker volume couldn't be found")
                    HTTP_CONFLICT ->
                        DockerVolumeRemoveConflictException(
                            response.messageOr("Docker volume is currently in use")
                        )
                    else -> null
                }
            }
        ) {
            dockerClient.removeVolumeCmd(name).exec()
        }
    }

    override fun createContainer(command: DockerContainerCreateCommand): String {
        return dockerOperationCall(
            responseMapper = { response ->
                when {
                    response.statusCode == HTTP_BAD_REQUEST ||
                            response.statusCode == HTTP_FORBIDDEN ||
                            response.statusCode == HTTP_NOT_ACCEPTABLE ->
                        DockerContainerCreateRejectedException(
                            response.messageOr("Docker container creation was rejected")
                        )

                    response.statusCode == HTTP_NOT_FOUND ->
                        DockerContainerCreateDependencyNotFoundException(
                            "Docker container create dependency couldn't be found"
                        )

                    response.statusCode == HTTP_CONFLICT ->
                        DockerContainerCreateNameConflictException(
                            response.messageOr("Docker container name is already in use")
                        )

                    response.isContainerRuntimeResourceConflict() ->
                        DockerContainerRuntimeResourceConflictException(
                            response.messageOr(
                                "Docker container runtime resource conflict"
                            )
                        )

                    else -> null
                }
            }
        ) {
            val portBindings = Ports()
            val exposedPorts = command.portBindings.map { binding ->
                val exposedPort = when (binding.protocol) {
                    DockerPortProtocol.TCP -> ExposedPort.tcp(binding.containerPort)
                    DockerPortProtocol.UDP -> ExposedPort.udp(binding.containerPort)
                }
                portBindings.bind(exposedPort, Ports.Binding.bindPort(binding.hostPort))
                exposedPort
            }
            val volumeMounts = command.volumeBindings.map { binding ->
                Mount()
                    .withType(MountType.VOLUME)
                    .withSource(binding.volumeName)
                    .withTarget(binding.mountPath)
                    .withReadOnly(binding.readOnly)
            }
            val restartPolicy = when (command.restartPolicy.type) {
                DockerRestartPolicyType.NO -> DockerJavaRestartPolicy.noRestart()
                DockerRestartPolicyType.ALWAYS -> DockerJavaRestartPolicy.alwaysRestart()
                DockerRestartPolicyType.ON_FAILURE -> DockerJavaRestartPolicy.onFailureRestart(
                    command.restartPolicy.maxRetries ?: 0
                )
                DockerRestartPolicyType.UNLESS_STOPPED ->
                    DockerJavaRestartPolicy.unlessStoppedRestart()
            }
            val hostConfig = HostConfig.newHostConfig()
                .withNetworkMode(command.networkName)
                .withPortBindings(portBindings)
                .withMounts(volumeMounts)
                .withRestartPolicy(restartPolicy)

            command.memoryLimitBytes?.let(hostConfig::withMemory)
            command.cpuLimitNanoCpus?.let(hostConfig::withNanoCPUs)

            val createCommand = dockerClient.createContainerCmd(
                "${command.image.repository}:${command.image.tag}"
            )
                .withName(command.name)
                .withEnv(command.env.map { (name, value) -> "$name=$value" })
                .withLabels(command.labels)
                .withHostConfig(hostConfig)

            command.command?.let(createCommand::withEntrypoint)
            command.args?.let(createCommand::withCmd)
            if (command.networkAliases.isNotEmpty()) {
                createCommand.withAliases(command.networkAliases)
            }
            if (exposedPorts.isNotEmpty()) {
                createCommand.withExposedPorts(exposedPorts)
            }

            createCommand.exec().id
        }
    }

    override fun listContainers(filter: DockerLabelsFilter): List<DockerContainerSummary> {
        return dockerOperationCall {
            val command = dockerClient.listContainersCmd()
                .withShowAll(true)

            if (filter.labels.isNotEmpty()) {
                command.withLabelFilter(filter.labels)
            }

            command.exec().map { it.toSummary() }
        }
    }

    override fun inspectContainer(containerId: String): DockerContainerDetails {
        return containerDockerCall {
            dockerClient.inspectContainerCmd(containerId)
                .exec()
                .toDetails()
        }
    }

    override fun getContainerStats(containerId: String): DockerContainerStats {
        return containerDockerCall {
            readSingleStats(containerId).toContainerStats()
        }
    }

    override fun getLogs(
        containerId: String,
        request: DockerContainerLogsRequest
    ): DockerContainerLogs {
        return containerDockerCall {
            readLogs(containerId, request)
        }
    }

    override fun startContainer(containerId: String) {
        lifecycleDockerCall(
            alreadyInTargetState = { DockerContainerAlreadyStartedException() },
            conflict = {
                DockerContainerLifecycleConflictException(
                    it.messageOr(DOCKER_CONTAINER_LIFECYCLE_CONFLICT_MESSAGE)
                )
            }
        ) {
            dockerClient.startContainerCmd(containerId).exec()
        }
    }

    override fun stopContainer(containerId: String) {
        lifecycleDockerCall(
            alreadyInTargetState = { DockerContainerAlreadyStoppedException() },
            conflict = {
                DockerContainerLifecycleConflictException(
                    it.messageOr(DOCKER_CONTAINER_LIFECYCLE_CONFLICT_MESSAGE)
                )
            }
        ) {
            dockerClient.stopContainerCmd(containerId).exec()
        }
    }

    override fun restartContainer(containerId: String) {
        lifecycleDockerCall(
            alreadyInTargetState = {
                DockerContainerLifecycleConflictException(
                    it.messageOr(DOCKER_CONTAINER_LIFECYCLE_CONFLICT_MESSAGE)
                )
            },
            conflict = {
                DockerContainerLifecycleConflictException(
                    it.messageOr(DOCKER_CONTAINER_LIFECYCLE_CONFLICT_MESSAGE)
                )
            }
        ) {
            dockerClient.restartContainerCmd(containerId).exec()
        }
    }

    override fun pauseContainer(containerId: String) {
        lifecycleDockerCall(
            alreadyInTargetState = { DockerContainerAlreadyPausedException() },
            conflict = { it.toPauseConflict() }
        ) {
            dockerClient.pauseContainerCmd(containerId).exec()
        }
    }

    override fun unpauseContainer(containerId: String) {
        lifecycleDockerCall(
            alreadyInTargetState = { DockerContainerNotPausedException() },
            conflict = { it.toUnpauseConflict() }
        ) {
            dockerClient.unpauseContainerCmd(containerId).exec()
        }
    }

    override fun deleteContainer(containerId: String) {
        dockerOperationCall(
            responseMapper = { response ->
                when (response.statusCode) {
                    HTTP_BAD_REQUEST,
                    HTTP_CONFLICT ->
                        DockerContainerRemoveConflictException(
                            response.messageOr("Docker container cannot be removed")
                        )
                    HTTP_NOT_FOUND -> containerNotFound()
                    else -> null
                }
            }
        ) {
            dockerClient.removeContainerCmd(containerId)
                .withForce(true)
                .withRemoveVolumes(true)
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
            throw exception
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
            throw exception
        }

        if (!completed) {
            dockerLogger.warn("Docker logs command timed out for container {}", containerId)
            throw DockerAgentUnavailableException(DOCKER_AGENT_UNAVAILABLE_MESSAGE)
        }

        return DockerContainerLogs(lines = lines)
    }

    private fun DockerJavaImage.toImageSummary(): DockerImageSummary {
        val references = repoTags
            ?.toList()
            .orEmpty()
            .map { it.toImageReference() }

        return DockerImageSummary(
            id = id,
            references = references,
            repoDigests = repoDigests?.toList().orEmpty(),
            parentId = parentId,
            createdEpochSeconds = created,
            sizeBytes = size,
            labels = labels.orEmpty()
        )
    }

    private fun DockerInspectImageResponse.toImageInfo(): DockerImageInfo {
        return DockerImageInfo(
            id = id.orEmpty(),
            parentId = parent,
            repoTags = repoTags.orEmpty().map { it.toImageReference() },
            repoDigests = repoDigests.orEmpty(),
            createdAt = created,
            dockerVersion = dockerVersion,
            os = os,
            osVersion = osVersion,
            architecture = arch,
            author = author,
            comment = comment,
            sizeBytes = size,
            graphDriver = graphDriver.toImageGraphDriver(),
            rootFs = rootFS.toImageRootFs(),
            config = config.toImageConfig()
        )
    }

    private fun String.toImageReference(): DockerImageReference {
        if (this == UNTAGGED_IMAGE_REFERENCE) {
            return DockerImageReference(
                raw = this,
                registry = null,
                namespace = null,
                repository = null,
                tag = null
            )
        }

        val tagSeparatorIndex = lastIndexOf(':')
            .takeIf { it > lastIndexOf('/') }
        val imageName = tagSeparatorIndex
            ?.let { substring(0, it) }
            ?: this
        val tag = tagSeparatorIndex?.let { substring(it + 1) }

        val imageNameParts = imageName
            .split("/")
            .filter { it.isNotBlank() }
        val firstPart = imageNameParts.firstOrNull()
        val hasRegistry = firstPart != null &&
            (firstPart.contains(".") || firstPart.contains(":") || firstPart == "localhost")
        val registry = if (hasRegistry) firstPart.toRegistryInfo() else null
        val pathParts = if (hasRegistry) imageNameParts.drop(1) else imageNameParts

        return DockerImageReference(
            raw = this,
            registry = registry,
            namespace = pathParts.dropLast(1)
                .joinToString("/")
                .takeIf { it.isNotBlank() },
            repository = pathParts.lastOrNull(),
            tag = tag
        )
    }

    private fun String.toRegistryInfo(): DockerImageRegistryInfo {
        val portSeparatorIndex = lastIndexOf(':')
        val port = portSeparatorIndex
            .takeIf { it >= 0 }
            ?.let { substring(it + 1).toIntOrNull() }

        return DockerImageRegistryInfo(
            address = port?.let { substring(0, portSeparatorIndex) } ?: this,
            port = port
        )
    }

    private fun ContainerConfig?.toImageConfig(): DockerImageConfig? {
        if (this == null) {
            return null
        }

        val env = env.toEnvMap()

        return DockerImageConfig(
            env = env,
            cmd = cmd.orEmpty().toList(),
            entrypoint = entrypoint.orEmpty().toList(),
            workingDir = workingDir,
            user = user,
            labels = labels.orEmpty(),
            exposedPorts = exposedPorts.orEmpty().map {
                DockerExposedPort(
                    port = it.port,
                    type = it.protocol.toString()
                )
            }
        )
    }

    private fun DockerJavaGraphDriver?.toImageGraphDriver(): DockerImageGraphDriver? {
        return this?.let {
            DockerImageGraphDriver(
                name = name,
                data = data.toGraphDataMap()
            )
        }
    }

    private fun DockerJavaGraphData?.toGraphDataMap(): Map<String, String> {
        if (this == null) {
            return emptyMap()
        }

        return mapOf(
            "rootDir" to rootDir,
            "deviceId" to deviceId,
            "deviceName" to deviceName,
            "deviceSize" to deviceSize,
            "dir" to dir
        ).mapNotNull { (key, value) ->
            value?.let { key to it }
        }.toMap()
    }

    private fun DockerJavaRootFs?.toImageRootFs(): DockerImageRootFs? {
        return this?.let {
            DockerImageRootFs(
                type = type,
                layers = layers.orEmpty()
            )
        }
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

    private fun <T> dockerHttpCall(operation: () -> T): T {
        return try {
            operation()
        } catch (exception: DockerException) {
            throw DockerHttpResponseException(
                statusCode = exception.httpStatus,
                responseMessage = exception.responseMessage(),
                cause = exception
            )
        }
    }

    private fun <T> dockerTransportCall(operation: () -> T): T {
        return try {
            dockerHttpCall(operation)
        } catch (exception: DockerHttpResponseException) {
            throw exception
        } catch (exception: DockerClientException) {
            if (exception.hasIoCause()) {
                throw exception.toAgentUnavailable()
            }

            throw exception
        } catch (exception: RuntimeException) {
            if (exception.hasIoCause()) {
                throw exception.toAgentUnavailable()
            }

            throw exception
        }
    }

    private fun <T> dockerOperationCall(
        responseMapper: (DockerHttpResponseException) -> RuntimeException? = { null },
        operation: () -> T
    ): T {
        return try {
            dockerTransportCall(operation)
        } catch (exception: DockerHttpResponseException) {
            val mappedException = responseMapper(exception)
            if (mappedException != null) {
                throw mappedException.causedBy(exception)
            }

            logDockerCallFailure(exception)
            throw DockerOperationFailedException().causedBy(exception)
        } catch (exception: DockerClientException) {
            logDockerCallFailure(exception)
            throw DockerOperationFailedException().causedBy(exception)
        }
    }

    private fun <T> containerDockerCall(operation: () -> T): T {
        return dockerOperationCall(
            responseMapper = { response ->
                if (response.statusCode == HTTP_NOT_FOUND) {
                    containerNotFound()
                } else {
                    null
                }
            },
            operation = operation
        )
    }

    private fun lifecycleDockerCall(
        alreadyInTargetState: (DockerHttpResponseException) -> RuntimeException,
        conflict: (DockerHttpResponseException) -> RuntimeException,
        operation: () -> Unit
    ) {
        dockerOperationCall(
            responseMapper = { response ->
                val lifecycleStateConflict = response.toLifecycleStateConflict()
                when {
                    response.statusCode == HTTP_NOT_MODIFIED ->
                        alreadyInTargetState(response)
                    response.statusCode == HTTP_CONFLICT ->
                        conflict(response)
                    response.statusCode == HTTP_NOT_FOUND ->
                        containerNotFound()
                    lifecycleStateConflict != null ->
                        lifecycleStateConflict
                    response.isContainerRuntimeResourceConflict() ->
                        DockerContainerRuntimeResourceConflictException(
                            response.messageOr(
                                "Docker container runtime resource conflict"
                            )
                        )
                    else -> null
                }
            },
            operation = operation
        )
    }

    private fun mapImagePullHttpFailure(
        response: DockerHttpResponseException
    ): RuntimeException {
        val mappedException = when (response.statusCode) {
            HTTP_UNAUTHORIZED,
            HTTP_FORBIDDEN ->
                DockerImagePullAccessDeniedException(
                    response.messageOr("Docker registry access was denied")
                )
            HTTP_NOT_FOUND ->
                DockerImageNotFoundException(
                    response.messageOr("Docker image couldn't be found")
                )
            in HTTP_SERVER_ERROR_RANGE ->
                DockerImageRegistryUnavailableException(
                    response.messageOr("Docker image registry is unavailable")
                )
            else ->
                DockerImagePullFailedException(
                    response.messageOr("Docker image pull failed")
                )
        }

        return mappedException.causedBy(response)
    }

    private fun mapImagePullFailure(
        failure: DockerImagePullFailure,
        cause: RuntimeException? = null
    ): RuntimeException {
        val normalizedMessage = failure.message.lowercase()
        val mappedException = when {
            failure.statusCode == HTTP_UNAUTHORIZED ||
                failure.statusCode == HTTP_FORBIDDEN ||
                PULL_ACCESS_DENIED_MARKERS.any(normalizedMessage::contains) ->
                DockerImagePullAccessDeniedException(failure.message)
            failure.statusCode == HTTP_NOT_FOUND ||
                PULL_NOT_FOUND_MARKERS.any(normalizedMessage::contains) ->
                DockerImageNotFoundException(failure.message)
            failure.statusCode?.let(HTTP_SERVER_ERROR_RANGE::contains) == true ||
                PULL_REGISTRY_UNAVAILABLE_MARKERS.any(normalizedMessage::contains) ->
                DockerImageRegistryUnavailableException(failure.message)
            else -> DockerImagePullFailedException(failure.message)
        }

        return if (cause != null) {
            mappedException.causedBy(cause)
        } else {
            mappedException
        }
    }

    private fun DockerHttpResponseException.toLifecycleStateConflict(): RuntimeException? {
        val normalized = responseMessage.lowercase()

        return when {
            "already started" in normalized || "already running" in normalized ->
                DockerContainerAlreadyStartedException()
            "already stopped" in normalized ->
                DockerContainerAlreadyStoppedException()
            "already paused" in normalized ->
                DockerContainerAlreadyPausedException()
            "not paused" in normalized ->
                DockerContainerNotPausedException()
            "not running" in normalized ->
                DockerContainerNotRunningException()
            else -> null
        }
    }

    private fun DockerHttpResponseException.toPauseConflict(): RuntimeException {
        return toLifecycleStateConflict()
            ?: DockerContainerLifecycleConflictException(
                messageOr(DOCKER_CONTAINER_LIFECYCLE_CONFLICT_MESSAGE)
            )
    }

    private fun DockerHttpResponseException.toUnpauseConflict(): RuntimeException {
        return toLifecycleStateConflict()
            ?: DockerContainerLifecycleConflictException(
                messageOr(DOCKER_CONTAINER_LIFECYCLE_CONFLICT_MESSAGE)
            )
    }

    private fun DockerHttpResponseException.isContainerRuntimeResourceConflict(): Boolean {
        if (statusCode != HTTP_INTERNAL_SERVER_ERROR) {
            return false
        }

        val normalized = responseMessage.lowercase()
        return CONTAINER_RUNTIME_RESOURCE_CONFLICT_MARKERS.any(normalized::contains) ||
            ("network" in normalized && "not found" in normalized)
    }

    private fun DockerHttpResponseException.messageOr(defaultMessage: String): String {
        return responseMessage.takeIf { it.isNotBlank() } ?: defaultMessage
    }

    private fun DockerException.responseMessage(): String {
        val statusPrefix = "Status $httpStatus:"
        return message
            ?.removePrefix(statusPrefix)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Docker request failed with status $httpStatus"
    }

    private fun RuntimeException.toAgentUnavailable(): DockerAgentUnavailableException {
        logDockerCallFailure(this)
        return DockerAgentUnavailableException(DOCKER_AGENT_UNAVAILABLE_MESSAGE)
            .causedBy(this)
    }

    private fun Throwable.hasIoCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is IOException) {
                return true
            }
            current = current.cause
        }

        return false
    }

    private fun <T : RuntimeException> T.causedBy(cause: Throwable): T {
        initCause(cause)
        return this
    }

    private fun logDockerCallFailure(exception: RuntimeException) {
        dockerLogger.warn("Docker agent call failed: {}", exception.safeMessage(), exception)
    }

    private fun RuntimeException.safeMessage(): String {
        return message?.takeIf { it.isNotBlank() } ?: this::class.simpleName.orEmpty()
    }

    private fun containerNotFound(): DockerContainerNotFoundException =
        DockerContainerNotFoundException("Docker container couldn't be found")

    private companion object {
        val dockerLogger: Logger = LoggerFactory.getLogger(LocalDockerAgentClient::class.java)

        const val DOCKER_AGENT_UNAVAILABLE_MESSAGE = "Docker agent is unavailable"
        const val DOCKER_CONTAINER_LIFECYCLE_CONFLICT_MESSAGE = "Docker container lifecycle state conflict"
        const val DOCKER_IMAGE_REMOVE_CONFLICT_MESSAGE = "Docker image remove conflict"
        const val UNTAGGED_IMAGE_REFERENCE = "<none>:<none>"
        const val STATS_TIMEOUT_SECONDS = 5L
        const val LOGS_TIMEOUT_SECONDS = 10L

        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_NOT_ACCEPTABLE = 406
        const val HTTP_CONFLICT = 409
        const val HTTP_NOT_MODIFIED = 304
        const val HTTP_INTERNAL_SERVER_ERROR = 500

        val HTTP_SERVER_ERROR_RANGE = 500..599
        val PULL_ACCESS_DENIED_MARKERS = listOf(
            "pull access denied",
            "requested access to the resource is denied",
            "authentication required",
            "insufficient_scope",
            "unauthorized"
        )
        val PULL_NOT_FOUND_MARKERS = listOf(
            "manifest unknown",
            "name unknown",
            "no such image",
            "repository does not exist",
            "not found"
        )
        val PULL_REGISTRY_UNAVAILABLE_MARKERS = listOf(
            "service unavailable",
            "registry is unavailable",
            "connection refused",
            "context deadline exceeded",
            "i/o timeout",
            "no route to host",
            "temporary failure",
            "tls handshake timeout"
        )
        val CONTAINER_RUNTIME_RESOURCE_CONFLICT_MARKERS = listOf(
            "port is already allocated",
            "address already in use",
            "failed programming external connectivity",
            "error while mounting volume",
            "failed to mount",
            "no such volume",
            "already exists in network"
        )
    }

    private data class DiskIoBytes(
        val readBytes: Long?,
        val writeBytes: Long?
    )
}
