package io.dockistry.platform.secrets.api

import io.dockistry.platform.iam.application.CurrentUserProvider
import io.dockistry.platform.secrets.application.ProjectSecretQueryService
import io.dockistry.platform.secrets.application.SecretService
import io.dockistry.platform.secrets.domain.dto.ProjectSecretDto
import io.dockistry.platform.shared.api.ApiResponse
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/projects/{projectId}/secrets")
class ProjectSecretController(
    private val currentUserProvider: CurrentUserProvider,
    private val projectSecretQueryService: ProjectSecretQueryService,
    private val secretService: SecretService
) {
    @GetMapping("")
    fun list(@PathVariable projectId: UUID): ApiResponse<List<ProjectSecretDto>> =
        ApiResponse(
            projectSecretQueryService.list(projectId, currentUserProvider.getUser().principal)
        )

    @GetMapping("/{secretId}")
    fun get(
        @PathVariable projectId: UUID,
        @PathVariable secretId: String
    ): ApiResponse<ProjectSecretDto> =
        ApiResponse(
            projectSecretQueryService.get(
                projectId = projectId,
                secretId = secretId,
                principal = currentUserProvider.getUser().principal
            )
        )

    @DeleteMapping("/{secretId}/delegation")
    fun forceRevoke(
        @PathVariable projectId: UUID,
        @PathVariable secretId: String
    ): ApiResponse<String> {
        secretService.forceRevoke(
            secretId = secretId,
            projectId = projectId,
            principal = currentUserProvider.getUser().principal
        )
        return ApiResponse("ok")
    }
}
