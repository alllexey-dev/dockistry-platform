package io.dockistry.platform.testsupport.concurrency

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.DockerVolumeNotFoundException
import io.dockistry.platform.docker.domain.model.DockerAgentInfo
import io.dockistry.platform.docker.domain.model.DockerContainerCreateCommand
import io.dockistry.platform.docker.domain.model.DockerContainerDetails
import io.dockistry.platform.docker.domain.model.DockerContainerLogs
import io.dockistry.platform.docker.domain.model.DockerContainerLogsRequest
import io.dockistry.platform.docker.domain.model.DockerContainerState
import io.dockistry.platform.docker.domain.model.DockerContainerStats
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerImageInfo
import io.dockistry.platform.docker.domain.model.DockerImageRef
import io.dockistry.platform.docker.domain.model.DockerImageSummary
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.docker.domain.model.DockerNetworkRef
import io.dockistry.platform.docker.domain.model.DockerVolumeRef
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.AFTER_CONTAINER_CREATE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.AFTER_NETWORK_CREATE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.AFTER_VOLUME_CREATE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_CONTAINER_CREATE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_CONTAINER_START
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_NETWORK_CREATE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_VOLUME_CREATE
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SimulatedDeployExecutorDeath(
    val deathPoint: FakeDockerAgentClient.ExecutorDeathPoint
) : Error("Simulated deploy executor death at $deathPoint")

class SimulatedContainerStartFailure :
    RuntimeException("Simulated container start failure")

class FakeDockerAgentClient(
    private val concurrency: ConcurrencyTestSupport
) : DockerAgentClient {
    val pulledImages: MutableSet<DockerImageRef> = ConcurrentHashMap.newKeySet()
    val createdNetworks = ConcurrentHashMap<String, DockerNetworkRef>()
    val networksByName = ConcurrentHashMap<String, DockerNetworkRef>()
    val createdVolumes = ConcurrentHashMap<String, DockerVolumeRef>()
    val activeVolumeNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val removedVolumeNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val createdContainerCommands =
        ConcurrentHashMap<String, DockerContainerCreateCommand>()
    val activeContainerIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val deletedContainerIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val containerStates =
        ConcurrentHashMap<String, DockerContainerState>()

    @Volatile
    var startContainerFailure: RuntimeException? = null

    @Volatile
    var executorDeathPoint: ExecutorDeathPoint? = null

    fun reset() {
        pulledImages.clear()
        createdNetworks.clear()
        networksByName.clear()
        createdVolumes.clear()
        activeVolumeNames.clear()
        removedVolumeNames.clear()
        createdContainerCommands.clear()
        activeContainerIds.clear()
        deletedContainerIds.clear()
        containerStates.clear()
        startContainerFailure = null
        executorDeathPoint = null
    }

    override fun status(): DockerAgentInfo {
        error("Docker agent status flow not used in this test")
    }

    override fun listImages(): List<DockerImageSummary> {
        error("Docker image listing flow not used in this test")
    }

    override fun inspectImage(imageId: String): DockerImageInfo {
        error("Docker image inspection flow not used in this test")
    }

    override fun pullImage(imageRef: DockerImageRef) {
        pulledImages.add(imageRef)
    }

    override fun removeImage(imageId: String, force: Boolean) {
        error("Docker image removal flow not used in this test")
    }

    override fun findNetworkByName(name: String): DockerNetworkRef? {
        return networksByName[name]
    }

    override fun createNetwork(
        name: String,
        labels: Map<String, String>
    ): DockerNetworkRef {
        concurrency.checkpoint(BEFORE_NETWORK_CREATE)

        val network = DockerNetworkRef(
            id = UUID.randomUUID().toString(),
            name = name,
            labels = labels.toMap()
        )
        createdNetworks[network.id] = network
        networksByName[name] = network
        concurrency.checkpoint(AFTER_NETWORK_CREATE)
        return network
    }

    override fun removeNetwork(networkId: String) {
        error("Docker network removal flow not used in this test")
    }

    override fun createVolume(
        name: String,
        labels: Map<String, String>
    ) {
        concurrency.checkpoint(BEFORE_VOLUME_CREATE)

        val volume = DockerVolumeRef(
            name = name,
            labels = labels.toMap()
        )
        createdVolumes[name] = volume
        activeVolumeNames.add(name)
        concurrency.checkpoint(AFTER_VOLUME_CREATE)
    }

    override fun findVolumeByName(name: String): DockerVolumeRef? {
        if (!activeVolumeNames.contains(name)) {
            return null
        }
        return createdVolumes[name]
    }

    override fun listVolumes(filter: DockerLabelsFilter): List<DockerVolumeRef> {
        return activeVolumeNames.mapNotNull { name ->
            createdVolumes[name]?.takeIf { it.labels.matches(filter) }
        }
    }

    override fun removeVolume(name: String) {
        if (!activeVolumeNames.remove(name)) {
            throw DockerVolumeNotFoundException("Docker volume $name wasn't found")
        }
        removedVolumeNames.add(name)
    }

    override fun createContainer(command: DockerContainerCreateCommand): String {
        concurrency.checkpoint(BEFORE_CONTAINER_CREATE)

        val containerId = UUID.randomUUID().toString()
        createdContainerCommands[containerId] = command.copy(
            env = command.env.toMap(),
            labels = command.labels.toMap(),
            command = command.command?.toList(),
            args = command.args?.toList(),
            networkAliases = command.networkAliases.toList(),
            portBindings = command.portBindings.toList(),
            volumeBindings = command.volumeBindings.toList()
        )
        activeContainerIds.add(containerId)
        containerStates[containerId] = DockerContainerState.CREATED
        concurrency.checkpoint(AFTER_CONTAINER_CREATE)
        throwIfExecutorDiesAt(
            ExecutorDeathPoint.AFTER_CONTAINER_CREATION
        )
        return containerId
    }

    override fun listContainers(
        filter: DockerLabelsFilter
    ): List<DockerContainerSummary> {
        return activeContainerIds.mapNotNull { containerId ->
            val command = createdContainerCommands[containerId]
                ?: return@mapNotNull null
            if (!command.labels.matches(filter)) {
                return@mapNotNull null
            }

            DockerContainerSummary(
                id = containerId,
                name = command.name,
                names = listOf(command.name),
                image = "${command.image.repository}:${command.image.tag}",
                imageId = null,
                command = command.command?.joinToString(" "),
                createdEpochSeconds = null,
                state = containerStates[containerId]
                    ?: DockerContainerState.UNKNOWN,
                status = null,
                ports = emptyList(),
                labels = command.labels,
                sizeRwBytes = null,
                sizeRootFsBytes = null,
                networkMode = command.networkName,
                networks = emptyMap(),
                mounts = emptyList()
            )
        }
    }

    override fun inspectContainer(containerId: String): DockerContainerDetails {
        error("Docker container inspection flow not used in this test")
    }

    override fun getContainerStats(containerId: String): DockerContainerStats {
        error("Docker container stats flow not used in this test")
    }

    override fun getLogs(
        containerId: String,
        request: DockerContainerLogsRequest
    ): DockerContainerLogs {
        error("Docker container logs flow not used in this test")
    }

    override fun startContainer(containerId: String) {
        if (!activeContainerIds.contains(containerId)) {
            throw DockerContainerNotFoundException(
                "Docker container $containerId wasn't found"
            )
        }
        concurrency.checkpoint(BEFORE_CONTAINER_START)
        startContainerFailure?.let { throw it }
        containerStates[containerId] = DockerContainerState.RUNNING
        throwIfExecutorDiesAt(ExecutorDeathPoint.AFTER_CONTAINER_START)
    }

    override fun stopContainer(containerId: String) {
        error("Docker container stop flow not used in this test")
    }

    override fun restartContainer(containerId: String) {
        error("Docker container restart flow not used in this test")
    }

    override fun pauseContainer(containerId: String) {
        error("Docker container pause flow not used in this test")
    }

    override fun unpauseContainer(containerId: String) {
        error("Docker container unpause flow not used in this test")
    }

    override fun deleteContainer(containerId: String) {
        if (!activeContainerIds.remove(containerId)) {
            throw DockerContainerNotFoundException(
                "Docker container $containerId wasn't found"
            )
        }
        containerStates.remove(containerId)
        deletedContainerIds.add(containerId)
    }

    private fun throwIfExecutorDiesAt(deathPoint: ExecutorDeathPoint) {
        if (executorDeathPoint == deathPoint) {
            throw SimulatedDeployExecutorDeath(deathPoint)
        }
    }

    private fun Map<String, String>.matches(
        filter: DockerLabelsFilter
    ): Boolean {
        return filter.labels.all { (name, value) -> this[name] == value }
    }

    enum class ExecutorDeathPoint {
        AFTER_CONTAINER_CREATION,
        AFTER_CONTAINER_START
    }
}
