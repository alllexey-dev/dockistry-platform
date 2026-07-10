package io.dockistry.platform.services.api

import io.dockistry.platform.iam.application.CurrentUserProvider
import io.dockistry.platform.services.application.ManagedServiceDtoMapper
import io.dockistry.platform.services.application.ManagedServiceService
import io.dockistry.platform.services.domain.dto.ManagedServiceCreateRequest
import io.dockistry.platform.services.domain.dto.ManagedServiceDto
import io.dockistry.platform.services.domain.dto.ManagedServiceUpdateRequest
import io.dockistry.platform.shared.api.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/projects/{projectId}/services")
class ManagedServiceController(
    private val currentUserProvider: CurrentUserProvider,
    private val managedServiceService: ManagedServiceService,
    private val managedServiceDtoMapper: ManagedServiceDtoMapper
) {
    @GetMapping("")
    fun list(@PathVariable projectId: UUID): ApiResponse<List<ManagedServiceDto>> {
        val user = currentUserProvider.getUser()
        return ApiResponse(
            managedServiceDtoMapper.toDtos(
                managedServiceService.list(projectId, user.principal)
            )
        )
    }

    @PostMapping("")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable projectId: UUID,
        @RequestBody @Valid request: ManagedServiceCreateRequest
    ): ApiResponse<ManagedServiceDto> {
        val user = currentUserProvider.getUser()
        val command = managedServiceDtoMapper.toCreateCommand(
            projectId,
            user.principal,
            request
        )
        val service = managedServiceService.create(command)

        return ApiResponse(managedServiceDtoMapper.toDto(service))
    }

    @GetMapping("/{serviceId}")
    fun get(
        @PathVariable projectId: UUID,
        @PathVariable serviceId: UUID
    ): ApiResponse<ManagedServiceDto> {
        val user = currentUserProvider.getUser()
        return ApiResponse(
            managedServiceDtoMapper.toDto(
                managedServiceService.get(projectId, serviceId, user.principal)
            )
        )
    }

    @PatchMapping("/{serviceId}")
    fun update(
        @PathVariable projectId: UUID,
        @PathVariable serviceId: UUID,
        @RequestBody @Valid request: ManagedServiceUpdateRequest
    ): ApiResponse<ManagedServiceDto> {
        val user = currentUserProvider.getUser()
        val command = managedServiceDtoMapper.toUpdateCommand(
            projectId,
            serviceId,
            user.principal,
            request
        )
        val service = managedServiceService.update(command)

        return ApiResponse(managedServiceDtoMapper.toDto(service))
    }

    @DeleteMapping("/{serviceId}")
    fun delete(
        @PathVariable projectId: UUID,
        @PathVariable serviceId: UUID
    ): ApiResponse<String> {
        val user = currentUserProvider.getUser()
        managedServiceService.delete(projectId, serviceId, user.principal)
        return ApiResponse("ok")
    }
}
