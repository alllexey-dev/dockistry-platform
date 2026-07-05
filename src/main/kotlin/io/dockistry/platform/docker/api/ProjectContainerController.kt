package io.dockistry.platform.docker.api

import io.dockistry.platform.docker.application.ProjectContainerService
import io.dockistry.platform.docker.domain.dto.ContainerDetails
import io.dockistry.platform.docker.domain.dto.ContainerLogs
import io.dockistry.platform.docker.domain.dto.ContainerStats
import io.dockistry.platform.docker.domain.dto.ContainerSummary
import io.dockistry.platform.docker.domain.InvalidContainerLogsRequestException
import io.dockistry.platform.docker.domain.model.DockerContainerLogStream
import io.dockistry.platform.docker.domain.model.DockerContainerLogsRequest
import io.dockistry.platform.iam.application.CurrentUserProvider
import io.dockistry.platform.shared.api.ApiResponse
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
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

    @GetMapping("/{containerId}/logs")
    fun logs(
        @PathVariable projectId: UUID,
        @PathVariable containerId: String,
        @RequestParam(required = false) tail: Int?,
        @RequestParam(required = false) since: Instant?,
        @RequestParam(required = false) until: Instant?,
        @RequestParam(required = false) timestamps: Boolean?,
        @RequestParam(required = false) streams: String?
    ): ApiResponse<ContainerLogs> {
        val user = currentUserProvider.getUser()
        val request = DockerContainerLogsRequest(
            tail = tail ?: DockerContainerLogsRequest.DEFAULT_TAIL,
            since = since,
            until = until,
            timestamps = timestamps ?: false,
            streams = streams.toLogStreams()
        )

        return ApiResponse(
            projectContainerService.getLogs(
                projectId = projectId,
                containerId = containerId,
                principal = user.principal,
                request = request
            )
        )
    }

    @PostMapping("/{containerId}/start")
    fun start(
        @PathVariable projectId: UUID,
        @PathVariable containerId: String
    ): ApiResponse<String> {
        val user = currentUserProvider.getUser()
        projectContainerService.startContainer(projectId, containerId, user.principal)
        return ApiResponse("ok")
    }

    @PostMapping("/{containerId}/stop")
    fun stop(
        @PathVariable projectId: UUID,
        @PathVariable containerId: String
    ): ApiResponse<String> {
        val user = currentUserProvider.getUser()
        projectContainerService.stopContainer(projectId, containerId, user.principal)
        return ApiResponse("ok")
    }

    @PostMapping("/{containerId}/restart")
    fun restart(
        @PathVariable projectId: UUID,
        @PathVariable containerId: String
    ): ApiResponse<String> {
        val user = currentUserProvider.getUser()
        projectContainerService.restartContainer(projectId, containerId, user.principal)
        return ApiResponse("ok")
    }

    @PostMapping("/{containerId}/pause")
    fun pause(
        @PathVariable projectId: UUID,
        @PathVariable containerId: String
    ): ApiResponse<String> {
        val user = currentUserProvider.getUser()
        projectContainerService.pauseContainer(projectId, containerId, user.principal)
        return ApiResponse("ok")
    }

    @PostMapping("/{containerId}/unpause")
    fun unpause(
        @PathVariable projectId: UUID,
        @PathVariable containerId: String
    ): ApiResponse<String> {
        val user = currentUserProvider.getUser()
        projectContainerService.unpauseContainer(projectId, containerId, user.principal)
        return ApiResponse("ok")
    }

    @DeleteMapping("/{containerId}")
    fun delete(
        @PathVariable projectId: UUID,
        @PathVariable containerId: String
    ): ApiResponse<String> {
        val user = currentUserProvider.getUser()
        projectContainerService.deleteContainer(projectId, containerId, user.principal)
        return ApiResponse("ok")
    }

    private fun String?.toLogStreams(): Set<DockerContainerLogStream> {
        if (isNullOrBlank()) {
            return setOf(DockerContainerLogStream.STDOUT, DockerContainerLogStream.STDERR)
        }

        return split(",")
            .map { it.trim().lowercase() }
            .map { stream ->
                when (stream) {
                    "stdout" -> DockerContainerLogStream.STDOUT
                    "stderr" -> DockerContainerLogStream.STDERR
                    else -> throw InvalidContainerLogsRequestException(
                        "Container logs streams must contain only stdout or stderr"
                    )
                }
            }
            .toSet()
    }
}
