package io.dockistry.platform.docker.application

import io.dockistry.platform.docker.domain.DockistryManagedContainerAccessForbiddenException
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.dto.ContainerDetails
import io.dockistry.platform.docker.domain.dto.ContainerLogs
import io.dockistry.platform.docker.domain.dto.ContainerStats
import io.dockistry.platform.docker.domain.dto.ContainerSummary
import io.dockistry.platform.docker.domain.model.DockerContainerLogsRequest
import io.dockistry.platform.iam.application.PermissionCheckService
import io.dockistry.platform.iam.domain.InsufficientPermissionException
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.SystemPermissionNames
import org.springframework.stereotype.Service

@Service
class GenericContainerService(
    private val containerService: ContainerService,
    private val containerReadService: ContainerReadService,
    private val permissionCheckService: PermissionCheckService
) {
    fun listContainers(principal: Principal): List<ContainerSummary> {
        requireManagePermission(principal)
        return containerReadService.listContainers()
            .filterNot { DockerLabels.containsDockistryLabel(it.labels) }
    }

    fun inspectContainer(
        containerId: String,
        principal: Principal
    ): ContainerDetails {
        requireManagePermission(principal)
        return requireGenericContainer(containerId)
    }

    fun getContainerStats(
        containerId: String,
        principal: Principal
    ): ContainerStats {
        requireManagePermission(principal)
        val container = requireGenericContainer(containerId)
        return containerReadService.getContainerStats(container.id)
    }

    fun getLogs(
        containerId: String,
        principal: Principal,
        request: DockerContainerLogsRequest
    ): ContainerLogs {
        requireManagePermission(principal)
        val container = requireGenericContainer(containerId)
        return containerReadService.getLogs(container.id, request)
    }

    fun startContainer(containerId: String, principal: Principal) {
        requireManagePermission(principal)
        val container = requireGenericContainer(containerId)
        containerService.startContainer(container.id)
    }

    fun stopContainer(containerId: String, principal: Principal) {
        requireManagePermission(principal)
        val container = requireGenericContainer(containerId)
        containerService.stopContainer(container.id)
    }

    fun restartContainer(containerId: String, principal: Principal) {
        requireManagePermission(principal)
        val container = requireGenericContainer(containerId)
        containerService.restartContainer(container.id)
    }

    fun pauseContainer(containerId: String, principal: Principal) {
        requireManagePermission(principal)
        val container = requireGenericContainer(containerId)
        containerService.pauseContainer(container.id)
    }

    fun unpauseContainer(containerId: String, principal: Principal) {
        requireManagePermission(principal)
        val container = requireGenericContainer(containerId)
        containerService.unpauseContainer(container.id)
    }

    fun deleteContainer(containerId: String, principal: Principal) {
        requireManagePermission(principal)
        val container = requireGenericContainer(containerId)
        containerService.deleteContainer(container.id)
    }

    private fun requireManagePermission(principal: Principal) {
        if (
            !permissionCheckService.hasGlobalPermission(
                SystemPermissionNames.DOCKER_GENERIC_CONTAINERS_MANAGE,
                principal.id
            )
        ) {
            throw InsufficientPermissionException(
                "This principal is not allowed to manage generic Docker containers"
            )
        }
    }

    private fun requireGenericContainer(containerId: String): ContainerDetails {
        val container = containerReadService.inspectContainer(containerId)
        if (container.id != containerId) {
            throw DockerContainerNotFoundException(
                "Docker container couldn't be found by the provided full ID"
            )
        }
        if (DockerLabels.containsDockistryLabel(container.labels)) {
            throw DockistryManagedContainerAccessForbiddenException()
        }
        return container
    }
}
