package io.dockistry.platform.projects.application

import io.dockistry.platform.iam.domain.dto.PrincipalInfoDto
import io.dockistry.platform.iam.infrastructure.PrincipalInfoQueryRepository
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.dto.ProjectInfoDto
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Component

@Component
class ProjectInfoDtoMapper(
    private val principalInfoQueryRepository: PrincipalInfoQueryRepository
) {
    fun toDto(project: Project): ProjectInfoDto = toDtos(listOf(project)).single()

    fun toDtos(projects: List<Project>): List<ProjectInfoDto> {
        if (projects.isEmpty()) {
            return emptyList()
        }

        val projectOwners = principalInfoQueryRepository.getInfoByIds(
            projects.map { it.ownerPrincipal.id }
        )

        return projects.map { project ->
            map(
                project,
                projectOwners[project.ownerPrincipal.id]
                    ?: throw missingOwnerInfo(project)
            )
        }
    }

    private fun map(project: Project, ownerPrincipalInfoDto: PrincipalInfoDto): ProjectInfoDto {
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

    private fun missingOwnerInfo(project: Project): InternalDataIntegrityException {
        return InternalDataIntegrityException(
            "Project ${project.id} has no owner principal info " +
                "for owner ${project.ownerPrincipal.id}"
        )
    }
}
