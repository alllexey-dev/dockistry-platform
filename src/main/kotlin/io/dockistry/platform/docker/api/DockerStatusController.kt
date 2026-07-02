package io.dockistry.platform.docker.api

import io.dockistry.platform.docker.application.DockerStatusService
import io.dockistry.platform.docker.domain.dto.DockerAgentStatus
import io.dockistry.platform.shared.api.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/docker")
class DockerStatusController(
    private val dockerStatusService: DockerStatusService
) {
    @GetMapping("/status")
    fun status(): ApiResponse<DockerAgentStatus> {
        return ApiResponse(dockerStatusService.getStatus())
    }
}
