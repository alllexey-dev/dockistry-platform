package io.dockistry.platform.projects.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.InvalidProjectDescriptionException
import io.dockistry.platform.projects.domain.InvalidProjectNameException
import io.dockistry.platform.projects.domain.ProjectNotFoundException
import io.dockistry.platform.projects.domain.ProjectVersionConflictException
import io.dockistry.platform.projects.infrastructure.ProjectRepository
import io.dockistry.platform.shared.domain.ResourceDescriptionNormalizer
import io.dockistry.platform.shared.domain.ResourceNameNormalizer
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProjectUpdateExecutor(
    private val projectAccessGuard: ProjectAccessGuard,
    private val projectRepository: ProjectRepository
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun execute(
        projectId: UUID,
        name: String,
        description: String?,
        expectedVersion: Long,
        principal: Principal
    ): Project {
        projectAccessGuard.requireUpdatable(projectId, principal)

        val normalizedName = ResourceNameNormalizer.normalizeAndValidate(
            name,
            ::InvalidProjectNameException
        )
        val normalizedDescription = normalizeDescription(description)

        val updatedRows = projectRepository.updateWithExpectedVersion(
            projectId = projectId,
            name = normalizedName,
            description = normalizedDescription,
            expectedVersion = expectedVersion
        )

        if (updatedRows == 0) {
            return resolveConflict(
                projectId = projectId,
                name = name,
                description = description
            )
        }

        return reloadUpdated(projectId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resolveConflict(
        projectId: UUID,
        name: String,
        description: String?
    ): Project {
        val normalizedName = ResourceNameNormalizer.normalizeAndValidate(
            name,
            ::InvalidProjectNameException
        )
        val normalizedDescription = normalizeDescription(description)
        val project = projectRepository.findProjectById(projectId)
            ?: throw ProjectNotFoundException("Project with provided ID couldn't be found")

        if (
            project.name == normalizedName &&
            project.description == normalizedDescription
        ) {
            return project
        }

        throw ProjectVersionConflictException(
            "Project $projectId has been modified concurrently"
        )
    }

    private fun normalizeDescription(description: String?): String? =
        ResourceDescriptionNormalizer.normalizeAndValidate(
            description,
            ::InvalidProjectDescriptionException
        )

    private fun reloadUpdated(projectId: UUID): Project {
        return projectRepository.findProjectById(projectId)
            ?: throw InternalDataIntegrityException(
                "Project $projectId disappeared after it was updated"
            )
    }
}
