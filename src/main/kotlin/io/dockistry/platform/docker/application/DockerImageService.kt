package io.dockistry.platform.docker.application

import io.dockistry.platform.docker.domain.dto.ImageInfo
import io.dockistry.platform.docker.domain.dto.ImageSummary
import io.dockistry.platform.docker.domain.model.DockerImageRef
import io.dockistry.platform.iam.application.PermissionCheckService
import io.dockistry.platform.iam.domain.InsufficientPermissionException
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.SystemPermissionNames
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DockerImageService(
    private val containerService: ContainerService,
    private val permissionCheckService: PermissionCheckService,
    private val dockerApiDtoMapper: DockerApiDtoMapper
) {
    @Transactional(readOnly = true)
    fun listImages(principal: Principal): List<ImageSummary> {
        requireReadPermission(principal)
        return dockerApiDtoMapper.toImageSummaryDtos(containerService.listImages())
    }

    @Transactional(readOnly = true)
    fun inspectImage(imageId: String, principal: Principal): ImageInfo {
        requireReadPermission(principal)
        return dockerApiDtoMapper.toImageInfoDto(containerService.inspectImage(imageId))
    }

    @Transactional(readOnly = true)
    fun pullImage(imageRef: DockerImageRef, principal: Principal) {
        requireManagePermission(principal)
        containerService.pullImage(imageRef)
    }

    @Transactional(readOnly = true)
    fun removeImage(imageId: String, force: Boolean, principal: Principal) {
        requireManagePermission(principal)
        containerService.removeImage(imageId, force)
    }

    private fun requireReadPermission(principal: Principal) {
        requireGlobalPermission(
            permission = SystemPermissionNames.DOCKER_IMAGES_READ,
            principal = principal,
            operation = "view Docker images"
        )
    }

    private fun requireManagePermission(principal: Principal) {
        requireGlobalPermission(
            permission = SystemPermissionNames.DOCKER_IMAGES_MANAGE,
            principal = principal,
            operation = "manage Docker images"
        )
    }

    private fun requireGlobalPermission(
        permission: String,
        principal: Principal,
        operation: String
    ) {
        if (!permissionCheckService.hasGlobalPermission(permission, principal.id)) {
            throw InsufficientPermissionException(
                "This principal is not allowed to $operation"
            )
        }
    }
}
