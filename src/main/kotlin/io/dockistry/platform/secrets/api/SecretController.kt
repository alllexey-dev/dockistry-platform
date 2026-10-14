package io.dockistry.platform.secrets.api

import io.dockistry.platform.iam.application.CurrentUserProvider
import io.dockistry.platform.secrets.application.SecretService
import io.dockistry.platform.secrets.domain.dto.ProjectSecretDelegationRequest
import io.dockistry.platform.secrets.domain.dto.SecretCreateRequest
import io.dockistry.platform.secrets.domain.dto.SecretDelegationDto
import io.dockistry.platform.secrets.domain.dto.SecretDto
import io.dockistry.platform.secrets.domain.dto.SecretUpdateRequest
import io.dockistry.platform.secrets.domain.dto.SecretUsageDto
import io.dockistry.platform.secrets.domain.dto.SecretVersionCreateRequest
import io.dockistry.platform.secrets.domain.dto.SecretVersionDto
import io.dockistry.platform.shared.api.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
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

    @GetMapping("/{secretId}")
    fun get(@PathVariable secretId: String): ApiResponse<SecretDto> =
        ApiResponse(secretService.get(secretId, currentUserProvider.getUser().principal))

    @GetMapping("/{secretId}/versions")
    fun listVersions(@PathVariable secretId: String): ApiResponse<List<SecretVersionDto>> =
        ApiResponse(
            secretService.listVersions(secretId, currentUserProvider.getUser().principal)
        )

    @GetMapping("/{secretId}/usage")
    fun listUsage(@PathVariable secretId: String): ApiResponse<List<SecretUsageDto>> =
        ApiResponse(secretService.listUsage(secretId, currentUserProvider.getUser().principal))

    @GetMapping("/{secretId}/delegations")
    fun listDelegations(
        @PathVariable secretId: String
    ): ApiResponse<List<SecretDelegationDto>> =
        ApiResponse(
            secretService.listDelegations(secretId, currentUserProvider.getUser().principal)
        )

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

    @PutMapping("/{secretId}")
    fun update(
        @PathVariable secretId: String,
        @RequestBody request: SecretUpdateRequest
    ): ApiResponse<SecretDto> {
        val secret = secretService.update(
            secretId = secretId,
            name = request.name,
            description = request.description,
            expectedVersion = request.expectedVersion,
            principal = currentUserProvider.getUser().principal
        )

        return ApiResponse(secret)
    }

    @DeleteMapping("/{secretId}")
    fun delete(@PathVariable secretId: String): ApiResponse<String> {
        secretService.delete(secretId, currentUserProvider.getUser().principal)
        return ApiResponse("ok")
    }

    @PostMapping("/{secretId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    fun rotate(
        @PathVariable secretId: String,
        @RequestBody request: SecretVersionCreateRequest
    ): ApiResponse<SecretVersionDto> {
        val version = secretService.rotate(
            secretId = secretId,
            newPayload = request.values,
            principal = currentUserProvider.getUser().principal
        )

        return ApiResponse(version)
    }

    @DeleteMapping("/{secretId}/versions/{versionId}")
    fun deleteVersion(
        @PathVariable secretId: String,
        @PathVariable versionId: String
    ): ApiResponse<String> {
        secretService.deleteVersion(
            secretId = secretId,
            versionId = versionId,
            principal = currentUserProvider.getUser().principal
        )
        return ApiResponse("ok")
    }

    @DeleteMapping("/{secretId}/delegations/{projectId}")
    fun revoke(
        @PathVariable secretId: String,
        @PathVariable projectId: UUID
    ): ApiResponse<String> {
        secretService.revoke(
            secretId = secretId,
            projectId = projectId,
            principal = currentUserProvider.getUser().principal
        )
        return ApiResponse("ok")
    }
}
