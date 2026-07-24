package io.dockistry.platform.projects.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.domain.InvalidProjectSlugException
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.ProjectAlreadyExistsException
import io.dockistry.platform.projects.domain.ProjectNameNormalizer
import io.dockistry.platform.projects.domain.ProjectNotFoundException
import io.dockistry.platform.projects.domain.ProjectVersionConflictException
import io.dockistry.platform.projects.infrastructure.ProjectRepository
import io.dockistry.platform.shared.domain.SlugValidator
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
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
        slug: String,
        description: String?,
        expectedVersion: Long,
        principal: Principal
    ): Project {
        projectAccessGuard.requireUpdatable(projectId, principal)

        val normalizedName = ProjectNameNormalizer.normalizeAndValidate(name)
        val validatedSlug = validateSlug(slug)

        val updatedRows = try {
            projectRepository.updateWithExpectedVersion(
                projectId = projectId,
                name = normalizedName,
                slug = validatedSlug,
                description = description,
                expectedVersion = expectedVersion
            )
        } catch (_: ConstraintViolationException) {
            throw InvalidProjectSlugException("Project slug '$slug' is invalid")
        } catch (_: DataIntegrityViolationException) {
            throw ProjectAlreadyExistsException("Project with such name/slug already exists")
        }

        if (updatedRows == 0) {
            return resolveConflict(
                projectId = projectId,
                name = name,
                slug = slug,
                description = description
            )
        }

        return reloadUpdated(projectId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resolveConflict(
        projectId: UUID,
        name: String,
        slug: String,
        description: String?
    ): Project {
        val normalizedName = ProjectNameNormalizer.normalizeAndValidate(name)
        val validatedSlug = validateSlug(slug)
        val project = projectRepository.findProjectById(projectId)
            ?: throw ProjectNotFoundException("Project with provided ID couldn't be found")

        if (
            project.name == normalizedName &&
            project.slug == validatedSlug &&
            project.description == description
        ) {
            return project
        }

        throw ProjectVersionConflictException(
            "Project $projectId has been modified concurrently"
        )
    }

    private fun reloadUpdated(projectId: UUID): Project {
        return projectRepository.findProjectById(projectId)
            ?: throw InternalDataIntegrityException(
                "Project $projectId disappeared after it was updated"
            )
    }

    private fun validateSlug(slug: String): String {
        return SlugValidator.validate(slug) {
            InvalidProjectSlugException("Project slug '$it' is invalid")
        }
    }
}
