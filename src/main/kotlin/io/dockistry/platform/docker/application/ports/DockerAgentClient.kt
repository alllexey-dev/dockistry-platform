package io.dockistry.platform.docker.application.ports

import io.dockistry.platform.docker.domain.model.DockerAgentInfo
import io.dockistry.platform.docker.domain.model.DockerContainerDetails
import io.dockistry.platform.docker.domain.model.DockerContainerFilter
import io.dockistry.platform.docker.domain.model.DockerContainerLogs
import io.dockistry.platform.docker.domain.model.DockerContainerLogsRequest
import io.dockistry.platform.docker.domain.model.DockerContainerStats
import io.dockistry.platform.docker.domain.model.DockerContainerSummary

interface DockerAgentClient {
    fun status(): DockerAgentInfo

    fun listContainers(filter: DockerContainerFilter): List<DockerContainerSummary>

    fun inspectContainer(containerId: String): DockerContainerDetails

    fun getContainerStats(containerId: String): DockerContainerStats

    fun getLogs(containerId: String, request: DockerContainerLogsRequest): DockerContainerLogs
}
