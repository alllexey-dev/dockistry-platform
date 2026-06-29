package io.dockistry.platform.docker.api

import io.dockistry.platform.docker.application.ProjectContainerService
import io.dockistry.platform.docker.domain.dto.ContainerDetails
import io.dockistry.platform.docker.domain.dto.ContainerStats
import io.dockistry.platform.docker.domain.dto.ContainerSummary
import io.dockistry.platform.iam.application.CurrentUserProvider
import io.dockistry.platform.shared.api.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/projects/{projectId}/containers")
class ProjectContainerController(
    private val currentUserProvider: CurrentUserProvider,
    private val projectContainerService: ProjectContainerService
) {
    @GetMapping("")
    fun list(@PathVariable projectId: UUID): ApiResponse<List<ContainerSummary>> {
        val user = currentUserProvider.getUser()
        return ApiResponse(
            projectContainerService.listContainers(projectId, user.principal)
        )
    }

    @GetMapping("/{containerId}")
    fun inspect(
        @PathVariable projectId: UUID,
        @PathVariable containerId: String
    ): ApiResponse<ContainerDetails> {
        val user = currentUserProvider.getUser()
        return ApiResponse(
            projectContainerService.inspectContainer(projectId, containerId, user.principal)
        )
    }

    @GetMapping("/{containerId}/stats")
    fun stats(
        @PathVariable projectId: UUID,
        @PathVariable containerId: String
    ): ApiResponse<ContainerStats> {
        val user = currentUserProvider.getUser()
        return ApiResponse(
            projectContainerService.getContainerStats(projectId, containerId, user.principal)
        )
    }
}
