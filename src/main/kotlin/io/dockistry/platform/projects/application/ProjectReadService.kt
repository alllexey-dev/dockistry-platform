package io.dockistry.platform.projects.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.projects.domain.dto.ProjectInfoDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProjectReadService(
    private val projectService: ProjectService,
    private val projectInfoDtoMapper: ProjectInfoDtoMapper
) {
    @Transactional
    fun listVisibleProjects(user: User): List<ProjectInfoDto> =
        listVisibleProjects(user.principal)

    @Transactional
    fun listVisibleProjects(principal: Principal): List<ProjectInfoDto> {
        return projectInfoDtoMapper.toDtos(projectService.list(principal))
    }

    @Transactional
    fun getProjectInfo(id: UUID, user: User): ProjectInfoDto {
        return getProjectInfo(id, user.principal)
    }

    @Transactional
    fun getProjectInfo(id: UUID, principal: Principal): ProjectInfoDto {
        return projectInfoDtoMapper.toDto(projectService.get(id, principal))
    }
}
