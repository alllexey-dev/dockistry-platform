package io.dockistry.platform.services.api

import io.dockistry.platform.docker.application.ContainerLogsRequestMapper
import io.dockistry.platform.docker.domain.dto.ContainerDetails
import io.dockistry.platform.docker.domain.dto.ContainerLogs
import io.dockistry.platform.docker.domain.dto.ContainerStats
import io.dockistry.platform.iam.application.CurrentUserProvider
import io.dockistry.platform.services.application.ManagedServiceDtoMapper
import io.dockistry.platform.services.application.ManagedServiceQueryService
import io.dockistry.platform.services.application.ManagedServiceService
import io.dockistry.platform.services.domain.dto.ManagedServiceCreateRequest
import io.dockistry.platform.services.domain.dto.ManagedServiceDeployResponse
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/projects/{projectId}/services")
class ManagedServiceController(
    private val currentUserProvider: CurrentUserProvider,
    private val managedServiceService: ManagedServiceService,
    private val managedServiceQueryService: ManagedServiceQueryService,
    private val managedServiceDtoMapper: ManagedServiceDtoMapper,
    private val containerLogsRequestMapper: ContainerLogsRequestMapper
) {
    @GetMapping("")
    fun list(@PathVariable projectId: UUID): ApiResponse<List<ManagedServiceDto>> {
        val user = currentUserProvider.getUser()
        return ApiResponse(
            managedServiceDtoMapper.toDtos(
                managedServiceQueryService.list(projectId, user.principal)
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

        return ApiResponse(
            managedServiceDtoMapper.toDto(
                managedServiceQueryService.withKnownReferences(
                    service,
                    command.configuration.secretReferences
                )
            )
        )
    }

    @GetMapping("/{serviceId}")
    fun get(
        @PathVariable projectId: UUID,
        @PathVariable serviceId: UUID
    ): ApiResponse<ManagedServiceDto> {
        val user = currentUserProvider.getUser()
        return ApiResponse(
            managedServiceDtoMapper.toDto(
                managedServiceQueryService.get(
                    projectId,
                    serviceId,
                    user.principal
                )
            )
        )
    }

    @GetMapping("/{serviceId}/container")
    fun inspectContainer(
        @PathVariable projectId: UUID,
        @PathVariable serviceId: UUID
    ): ApiResponse<ContainerDetails> {
        val user = currentUserProvider.getUser()
        return ApiResponse(
            managedServiceService.inspectContainer(
                projectId,
                serviceId,
                user.principal
            )
        )
    }

    @GetMapping("/{serviceId}/container/stats")
    fun getContainerStats(
        @PathVariable projectId: UUID,
        @PathVariable serviceId: UUID
    ): ApiResponse<ContainerStats> {
        val user = currentUserProvider.getUser()
        return ApiResponse(
            managedServiceService.getContainerStats(
                projectId,
                serviceId,
                user.principal
            )
        )
    }

    @GetMapping("/{serviceId}/container/logs")
    fun getContainerLogs(
        @PathVariable projectId: UUID,
        @PathVariable serviceId: UUID,
        @RequestParam(required = false) tail: Int?,
        @RequestParam(required = false) since: Instant?,
        @RequestParam(required = false) until: Instant?,
        @RequestParam(required = false) timestamps: Boolean?,
        @RequestParam(required = false) streams: String?
    ): ApiResponse<ContainerLogs> {
        val user = currentUserProvider.getUser()
        val request = containerLogsRequestMapper.fromQueryParameters(
            tail = tail,
            since = since,
            until = until,
            timestamps = timestamps,
            streams = streams
        )

        return ApiResponse(
            managedServiceService.getContainerLogs(
                projectId = projectId,
                serviceId = serviceId,
                principal = user.principal,
                request = request
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

        return ApiResponse(
            managedServiceDtoMapper.toDto(
                managedServiceQueryService.withKnownReferences(
                    service,
                    command.configuration.secretReferences
                )
            )
        )
    }

    @PostMapping("/{serviceId}/deploy")
    fun deploy(
        @PathVariable projectId: UUID,
        @PathVariable serviceId: UUID,
        @RequestParam ignoreWarnings: Boolean = false
    ): ApiResponse<ManagedServiceDeployResponse> {
        val user = currentUserProvider.getUser()
        val containerId = managedServiceService.deploy(
            projectId,
            serviceId,
            user.principal,
            ignoreWarnings
        )
        return ApiResponse(ManagedServiceDeployResponse(containerId))
    }

    @PostMapping("/{serviceId}/start")
    fun start(
        @PathVariable projectId: UUID,
        @PathVariable serviceId: UUID
    ): ApiResponse<String> {
        val user = currentUserProvider.getUser()
        managedServiceService.start(projectId, serviceId, user.principal)
        return ApiResponse("ok")
    }

    @PostMapping("/{serviceId}/stop")
    fun stop(
        @PathVariable projectId: UUID,
        @PathVariable serviceId: UUID
    ): ApiResponse<String> {
        val user = currentUserProvider.getUser()
        managedServiceService.stop(projectId, serviceId, user.principal)
        return ApiResponse("ok")
    }

    @PostMapping("/{serviceId}/pause")
    fun pause(
        @PathVariable projectId: UUID,
        @PathVariable serviceId: UUID
    ): ApiResponse<String> {
        val user = currentUserProvider.getUser()
        managedServiceService.pause(projectId, serviceId, user.principal)
        return ApiResponse("ok")
    }

    @PostMapping("/{serviceId}/unpause")
    fun unpause(
        @PathVariable projectId: UUID,
        @PathVariable serviceId: UUID
    ): ApiResponse<String> {
        val user = currentUserProvider.getUser()
        managedServiceService.unpause(projectId, serviceId, user.principal)
        return ApiResponse("ok")
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
