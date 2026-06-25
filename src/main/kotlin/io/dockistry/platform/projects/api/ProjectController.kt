package io.dockistry.platform.projects.api

import io.dockistry.platform.iam.application.CurrentUserProvider
import io.dockistry.platform.projects.application.ProjectInfoDtoMapper
import io.dockistry.platform.projects.application.ProjectReadService
import io.dockistry.platform.projects.application.ProjectService
import io.dockistry.platform.projects.domain.dto.ProjectInfoDto
import io.dockistry.platform.projects.domain.dto.ProjectSaveRequest
import io.dockistry.platform.shared.api.ApiResponse
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
@RequestMapping("/api/projects")
class ProjectController(
    private val currentUserProvider: CurrentUserProvider,
    private val projectService: ProjectService,
    private val projectReadService: ProjectReadService,
    private val projectInfoDtoMapper: ProjectInfoDtoMapper
) {

    @GetMapping("")
    fun list(): ApiResponse<List<ProjectInfoDto>> {
        val user = currentUserProvider.getUser()
        return ApiResponse(projectReadService.listVisibleProjects(user))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ApiResponse<ProjectInfoDto> {
        val user = currentUserProvider.getUser()
        return ApiResponse(
            projectReadService.getProjectInfo(id, user)
        )
    }

    @PostMapping("")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody saveRequest: ProjectSaveRequest): ApiResponse<ProjectInfoDto> {
        val user = currentUserProvider.getUser()

        val project = projectService.createForUser(
            name = saveRequest.name,
            slug = saveRequest.slug,
            description = saveRequest.description,
            user = user
        )

        return ApiResponse(projectInfoDtoMapper.toDto(project))
    }

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestBody saveRequest: ProjectSaveRequest
    ): ApiResponse<ProjectInfoDto> {
        val user = currentUserProvider.getUser()
        val project = projectService.updateForUser(
            projectId = id,
            name = saveRequest.name,
            slug = saveRequest.slug,
            description = saveRequest.description,
            user = user
        )
        return ApiResponse(
            projectInfoDtoMapper.toDto(project)
        )
    }

    @PostMapping("/{id}/archive")
    fun archive(@PathVariable id: UUID): ApiResponse<ProjectInfoDto> {
        val user = currentUserProvider.getUser()
        val project = projectService.archiveForUser(id, user)
        return ApiResponse(projectInfoDtoMapper.toDto(project))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): ApiResponse<String> {
        val user = currentUserProvider.getUser()
        projectService.deleteForUser(id, user)
        return ApiResponse("ok")
    }
}
