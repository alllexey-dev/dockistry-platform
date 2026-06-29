package io.dockistry.platform.docker.application.ports

import io.dockistry.platform.docker.domain.dto.ContainerDetails
import io.dockistry.platform.docker.domain.dto.ContainerFilter
import io.dockistry.platform.docker.domain.dto.ContainerStats
import io.dockistry.platform.docker.domain.dto.ContainerSummary
import io.dockistry.platform.docker.domain.dto.DockerAgentStatus

interface DockerAgentClient {
    fun status(): DockerAgentStatus

    fun listContainers(filter: ContainerFilter): List<ContainerSummary>

    fun inspectContainer(containerId: String): ContainerDetails

    fun getContainerStats(containerId: String): ContainerStats
}
