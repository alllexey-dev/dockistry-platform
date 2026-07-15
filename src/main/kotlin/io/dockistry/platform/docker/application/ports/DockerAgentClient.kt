package io.dockistry.platform.docker.application.ports

import io.dockistry.platform.docker.domain.model.DockerAgentInfo
import io.dockistry.platform.docker.domain.model.DockerContainerCreateCommand
import io.dockistry.platform.docker.domain.model.DockerContainerDetails
import io.dockistry.platform.docker.domain.model.DockerContainerFilter
import io.dockistry.platform.docker.domain.model.DockerContainerLogs
import io.dockistry.platform.docker.domain.model.DockerContainerLogsRequest
import io.dockistry.platform.docker.domain.model.DockerContainerStats
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerImageInfo
import io.dockistry.platform.docker.domain.model.DockerImageRef
import io.dockistry.platform.docker.domain.model.DockerImageSummary
import io.dockistry.platform.docker.domain.model.DockerNetworkRef

interface DockerAgentClient {
    fun status(): DockerAgentInfo

    fun listImages(): List<DockerImageSummary>

    fun inspectImage(imageId: String): DockerImageInfo

    fun pullImage(imageRef: DockerImageRef)

    fun removeImage(imageId: String, force: Boolean)

    fun findNetworkByName(name: String): DockerNetworkRef?

    fun createNetwork(name: String, labels: Map<String, String>): DockerNetworkRef

    fun removeNetwork(networkId: String)

    fun createVolume(name: String, labels: Map<String, String>)

    fun removeVolume(name: String)

    fun createContainer(command: DockerContainerCreateCommand): String

    fun listContainers(filter: DockerContainerFilter): List<DockerContainerSummary>

    fun inspectContainer(containerId: String): DockerContainerDetails

    fun getContainerStats(containerId: String): DockerContainerStats

    fun getLogs(containerId: String, request: DockerContainerLogsRequest): DockerContainerLogs

    fun startContainer(containerId: String)

    fun stopContainer(containerId: String)

    fun restartContainer(containerId: String)

    fun pauseContainer(containerId: String)

    fun unpauseContainer(containerId: String)

    fun deleteContainer(containerId: String)
}
