package io.dockistry.platform.docker.application

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.model.DockerAgentInfo
import io.dockistry.platform.docker.domain.model.DockerContainerCreateCommand
import io.dockistry.platform.docker.domain.model.DockerContainerDetails
import io.dockistry.platform.docker.domain.model.DockerContainerLogs
import io.dockistry.platform.docker.domain.model.DockerContainerLogsRequest
import io.dockistry.platform.docker.domain.model.DockerContainerStats
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerImageInfo
import io.dockistry.platform.docker.domain.model.DockerImageRef
import io.dockistry.platform.docker.domain.model.DockerImageSummary
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.docker.domain.model.DockerNetworkRef
import io.dockistry.platform.docker.domain.model.DockerVolumeRef
import org.springframework.stereotype.Service

@Service
class ContainerService(
    private val dockerAgentClient: DockerAgentClient
) {
    fun status(): DockerAgentInfo {
        return dockerAgentClient.status()
    }

    fun listImages(): List<DockerImageSummary> {
        return dockerAgentClient.listImages()
    }

    fun inspectImage(imageId: String): DockerImageInfo {
        return dockerAgentClient.inspectImage(imageId)
    }

    fun pullImage(imageRef: DockerImageRef) {
        dockerAgentClient.pullImage(imageRef)
    }

    fun removeImage(imageId: String, force: Boolean) {
        dockerAgentClient.removeImage(imageId, force)
    }

    fun findNetworkByName(name: String): DockerNetworkRef? {
        return dockerAgentClient.findNetworkByName(name)
    }

    fun createNetwork(name: String, labels: Map<String, String>): DockerNetworkRef {
        return dockerAgentClient.createNetwork(name, labels)
    }

    fun removeNetwork(networkId: String) {
        dockerAgentClient.removeNetwork(networkId)
    }

    fun createVolume(name: String, labels: Map<String, String>) {
        dockerAgentClient.createVolume(name, labels)
    }

    fun findVolumeByName(name: String): DockerVolumeRef? {
        return dockerAgentClient.findVolumeByName(name)
    }

    fun listVolumes(filter: DockerLabelsFilter): List<DockerVolumeRef> {
        return dockerAgentClient.listVolumes(filter)
    }

    fun removeVolume(name: String) {
        dockerAgentClient.removeVolume(name)
    }

    fun createContainer(command: DockerContainerCreateCommand): String {
        return dockerAgentClient.createContainer(command)
    }

    fun listContainers(filter: DockerLabelsFilter): List<DockerContainerSummary> {
        return dockerAgentClient.listContainers(filter)
    }

    fun inspectContainer(containerId: String): DockerContainerDetails {
        return dockerAgentClient.inspectContainer(containerId)
    }

    fun getContainerStats(containerId: String): DockerContainerStats {
        return dockerAgentClient.getContainerStats(containerId)
    }

    fun getLogs(containerId: String, request: DockerContainerLogsRequest): DockerContainerLogs {
        return dockerAgentClient.getLogs(containerId, request)
    }

    fun startContainer(containerId: String) {
        dockerAgentClient.startContainer(containerId)
    }

    fun stopContainer(containerId: String) {
        dockerAgentClient.stopContainer(containerId)
    }

    fun restartContainer(containerId: String) {
        dockerAgentClient.restartContainer(containerId)
    }

    fun pauseContainer(containerId: String) {
        dockerAgentClient.pauseContainer(containerId)
    }

    fun unpauseContainer(containerId: String) {
        dockerAgentClient.unpauseContainer(containerId)
    }

    fun deleteContainer(containerId: String) {
        dockerAgentClient.deleteContainer(containerId)
    }
}
