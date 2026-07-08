package io.dockistry.platform.docker.api

import io.dockistry.platform.docker.application.DockerImageService
import io.dockistry.platform.docker.domain.dto.ImageInfo
import io.dockistry.platform.docker.domain.dto.ImageSummary
import io.dockistry.platform.docker.domain.dto.PullImageRequest
import io.dockistry.platform.docker.domain.model.DockerImageRef
import io.dockistry.platform.iam.application.CurrentUserProvider
import io.dockistry.platform.shared.api.ApiResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/docker/images")
class DockerImageController(
    private val currentUserProvider: CurrentUserProvider,
    private val dockerImageService: DockerImageService
) {
    @GetMapping("")
    fun list(): ApiResponse<List<ImageSummary>> {
        val user = currentUserProvider.getUser()
        return ApiResponse(dockerImageService.listImages(user.principal))
    }

    @GetMapping("/{imageId}")
    fun inspect(@PathVariable imageId: String): ApiResponse<ImageInfo> {
        val user = currentUserProvider.getUser()
        return ApiResponse(dockerImageService.inspectImage(imageId, user.principal))
    }

    @PostMapping("/pull")
    fun pull(@RequestBody @Valid request: PullImageRequest): ApiResponse<String> {
        val user = currentUserProvider.getUser()
        dockerImageService.pullImage(
            imageRef = DockerImageRef(
                repository = request.repository,
                tag = request.tag
            ),
            principal = user.principal
        )
        return ApiResponse("ok")
    }

    @DeleteMapping("/{imageId}")
    fun remove(
        @PathVariable imageId: String,
        @RequestParam(defaultValue = "false") force: Boolean
    ): ApiResponse<String> {
        val user = currentUserProvider.getUser()
        dockerImageService.removeImage(imageId, force, user.principal)
        return ApiResponse("ok")
    }
}
