package io.dockistry.platform.docker.application

import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.dto.ContainerDetails
import io.dockistry.platform.docker.domain.dto.ContainerLogs
import io.dockistry.platform.docker.domain.dto.ContainerStats
import io.dockistry.platform.docker.domain.dto.ContainerSummary
import io.dockistry.platform.docker.domain.model.DockerContainerLogsRequest
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.projects.domain.Project
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProjectContainerService(
    private val containerService: ContainerService,
    private val projectAccessGuard: ProjectAccessGuard,
    private val projectContainerAccessGuard: ProjectContainerAccessGuard,
    private val dockerApiDtoMapper: DockerApiDtoMapper
) {
    @Transactional(readOnly = true)
    fun listContainers(projectId: UUID, principal: Principal): List<ContainerSummary> {
        val project = projectAccessGuard.requireReadable(projectId, principal)
        return dockerApiDtoMapper.toSummaryDtos(
            containerService.listContainers(project.toContainerFilter())
        )
    }

    @Transactional(readOnly = true)
    fun inspectContainer(
        projectId: UUID,
        containerId: String,
        principal: Principal
    ): ContainerDetails {
        val access = projectContainerAccessGuard.requireReadableContainer(
            projectId,
            containerId,
            principal
        )
        return dockerApiDtoMapper.toDetailsDto(access.container)
    }

    @Transactional(readOnly = true)
    fun getContainerStats(
        projectId: UUID,
        containerId: String,
        principal: Principal
    ): ContainerStats {
        val access = projectContainerAccessGuard.requireReadableContainer(
            projectId,
            containerId,
            principal
        )
        return dockerApiDtoMapper.toStatsDto(
            containerService.getContainerStats(access.container.id)
        )
    }

    @Transactional(readOnly = true)
    fun getLogs(
        projectId: UUID,
        containerId: String,
        principal: Principal,
        request: DockerContainerLogsRequest
    ): ContainerLogs {
        val access = projectContainerAccessGuard.requireReadableContainer(
            projectId,
            containerId,
            principal
        )
        return dockerApiDtoMapper.toLogsDto(
            containerService.getLogs(access.container.id, request)
        )
    }

    @Transactional(readOnly = true)
    fun startContainer(projectId: UUID, containerId: String, principal: Principal) {
        val access = projectContainerAccessGuard.requireManageableContainer(
            projectId,
            containerId,
            principal
        )
        containerService.startContainer(access.container.id)
    }

    @Transactional(readOnly = true)
    fun stopContainer(projectId: UUID, containerId: String, principal: Principal) {
        val access = projectContainerAccessGuard.requireManageableContainer(
            projectId,
            containerId,
            principal
        )
        containerService.stopContainer(access.container.id)
    }

    @Transactional(readOnly = true)
    fun restartContainer(projectId: UUID, containerId: String, principal: Principal) {
        val access = projectContainerAccessGuard.requireManageableContainer(
            projectId,
            containerId,
            principal
        )
        containerService.restartContainer(access.container.id)
    }

    @Transactional(readOnly = true)
    fun pauseContainer(projectId: UUID, containerId: String, principal: Principal) {
        val access = projectContainerAccessGuard.requireManageableContainer(
            projectId,
            containerId,
            principal
        )
        containerService.pauseContainer(access.container.id)
    }

    @Transactional(readOnly = true)
    fun unpauseContainer(projectId: UUID, containerId: String, principal: Principal) {
        val access = projectContainerAccessGuard.requireManageableContainer(
            projectId,
            containerId,
            principal
        )
        containerService.unpauseContainer(access.container.id)
    }

    @Transactional(readOnly = true)
    fun deleteContainer(projectId: UUID, containerId: String, principal: Principal) {
        val access = projectContainerAccessGuard.requireManageableContainer(
            projectId,
            containerId,
            principal
        )
        containerService.deleteContainer(access.container.id)
    }

    private fun Project.toContainerFilter(): DockerLabelsFilter {
        return DockerLabelsFilter(
            labels = DockerLabels.forProject(id)
        )
    }

}
