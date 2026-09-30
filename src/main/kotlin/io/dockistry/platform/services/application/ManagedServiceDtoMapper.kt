package io.dockistry.platform.services.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.services.application.dto.ManagedServiceCreateCommand
import io.dockistry.platform.services.application.dto.ManagedServiceDetails
import io.dockistry.platform.services.application.dto.ManagedServiceUpdateCommand
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceConfigurationDraft
import io.dockistry.platform.services.domain.ManagedServiceSecretReference
import io.dockistry.platform.services.domain.dto.ManagedServiceCreateRequest
import io.dockistry.platform.services.domain.dto.ManagedServiceDto
import io.dockistry.platform.services.domain.dto.ManagedServiceUpdateRequest
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ManagedServiceDtoMapper {
    fun toDto(details: ManagedServiceDetails): ManagedServiceDto {
        val service = details.service
        return ManagedServiceDto(
            id = service.id,
            projectId = service.project.id,
            name = service.name,
            imageRef = service.imageRef.asString(),
            command = service.command,
            args = service.args,
            env = service.env,
            secretReferences = details.secretReferences,
            endpoints = service.endpoints,
            volumes = service.volumes,
            restartPolicy = service.restartPolicy,
            restartMaxRetries = service.restartMaxRetries,
            memoryLimitBytes = service.memoryLimitBytes,
            cpuLimitNanoCpus = service.cpuLimitNanoCpus,
            desiredState = service.desiredState,
            lifecycleStatus = service.lifecycleStatus,
            version = service.version,
            createdAt = service.createdAt,
            updatedAt = service.updatedAt
        )
    }

    fun toDtos(services: List<ManagedServiceDetails>): List<ManagedServiceDto> {
        return services.map(::toDto)
    }

    fun toCreateCommand(
        projectId: UUID,
        principal: Principal,
        request: ManagedServiceCreateRequest
    ): ManagedServiceCreateCommand {
        return ManagedServiceCreateCommand(
            projectId = projectId,
            principal = principal,
            name = request.name,
            configuration = request.toConfigurationDraft()
        )
    }

    fun toUpdateCommand(
        projectId: UUID,
        serviceId: UUID,
        principal: Principal,
        request: ManagedServiceUpdateRequest
    ): ManagedServiceUpdateCommand {
        return ManagedServiceUpdateCommand(
            projectId = projectId,
            serviceId = serviceId,
            principal = principal,
            expectedVersion = request.expectedVersion,
            configuration = request.toConfigurationDraft()
        )
    }

    private fun ManagedServiceCreateRequest.toConfigurationDraft() =
        ManagedServiceConfigurationDraft(
            imageRef = imageRef,
            command = command,
            args = args,
            env = env,
            secretReferences = secretReferences,
            endpoints = endpoints,
            volumes = volumes,
            restartPolicy = restartPolicy,
            restartMaxRetries = restartMaxRetries,
            memoryLimitBytes = memoryLimitBytes,
            cpuLimitNanoCpus = cpuLimitNanoCpus,
            desiredState = desiredState
        )

    private fun ManagedServiceUpdateRequest.toConfigurationDraft() =
        ManagedServiceConfigurationDraft(
            imageRef = imageRef,
            command = command,
            args = args,
            env = env,
            secretReferences = secretReferences,
            endpoints = endpoints,
            volumes = volumes,
            restartPolicy = restartPolicy,
            restartMaxRetries = restartMaxRetries,
            memoryLimitBytes = memoryLimitBytes,
            cpuLimitNanoCpus = cpuLimitNanoCpus,
            desiredState = desiredState
        )
}

fun ManagedService.toConfigurationDraft(secretReferences: List<ManagedServiceSecretReference>) =
    ManagedServiceConfigurationDraft(
        imageRef = imageRef.asString(),
        command = command,
        args = args,
        env = env,
        secretReferences = secretReferences,
        endpoints = endpoints,
        volumes = volumes,
        restartPolicy = restartPolicy,
        restartMaxRetries = restartMaxRetries,
        memoryLimitBytes = memoryLimitBytes,
        cpuLimitNanoCpus = cpuLimitNanoCpus,
        desiredState = desiredState
    )
