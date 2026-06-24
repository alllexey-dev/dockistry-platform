package io.dockistry.platform.projects.domain.dto

import io.dockistry.platform.iam.domain.dto.PrincipalInfoDto
import io.dockistry.platform.projects.domain.Project

object ProjectInfoDtoMapper {
    fun map(project: Project, ownerPrincipalInfoDto: PrincipalInfoDto): ProjectInfoDto {
        return ProjectInfoDto(
            id = project.id,
            name = project.name,
            slug = project.slug,
            description = project.description,
            ownerPrincipalInfo = ownerPrincipalInfoDto,
            status = project.status,
            createdAt = project.createdAt
        )
    }
}
