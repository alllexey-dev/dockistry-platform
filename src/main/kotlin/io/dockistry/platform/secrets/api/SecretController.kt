package io.dockistry.platform.secrets.api

import io.dockistry.platform.iam.application.CurrentUserProvider
import io.dockistry.platform.secrets.application.SecretService
import io.dockistry.platform.secrets.domain.dto.ProjectSecretDelegationRequest
import io.dockistry.platform.secrets.domain.dto.SecretCreateRequest
import io.dockistry.platform.secrets.domain.dto.SecretDto
import io.dockistry.platform.shared.api.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/secrets")
class SecretController(
    private val currentUserProvider: CurrentUserProvider,
    private val secretService: SecretService
) {
    @GetMapping
    fun list(): ApiResponse<List<SecretDto>> =
        ApiResponse(secretService.list(currentUserProvider.getUser().principal))

    @PostMapping("/{secretId}/delegate")
    @ResponseStatus(HttpStatus.CREATED)
    fun delegate(
        @PathVariable secretId: String,
        @RequestBody request: ProjectSecretDelegationRequest
    ): ApiResponse<UUID> {
        val delegationId = secretService.delegate(
            secretId = secretId,
            projectId = request.projectId,
            ownerPrincipal = currentUserProvider.getUser().principal
        )

        return ApiResponse(delegationId)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: SecretCreateRequest): ApiResponse<String> {
        val secretId = secretService.create(
            name = request.name,
            description = request.description,
            values = request.values,
            ownerPrincipal = currentUserProvider.getUser().principal
        )

        return ApiResponse(secretId)
    }
}
