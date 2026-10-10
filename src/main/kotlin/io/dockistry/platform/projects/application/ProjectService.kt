package io.dockistry.platform.projects.application

import io.dockistry.platform.iam.application.PermissionCheckService
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.iam.infrastructure.ScopeQueryRepository
import io.dockistry.platform.projects.application.ports.ProjectManagedServicesExistencePort
import io.dockistry.platform.projects.application.ports.ProjectNetworkRemovalPort
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.ProjectAlreadyExistsException
import io.dockistry.platform.projects.domain.ProjectDeletionBlockedByManagedServicesException
import io.dockistry.platform.projects.domain.ProjectNotFoundException
import io.dockistry.platform.projects.infrastructure.ProjectRepository
import io.dockistry.platform.shared.domain.ResourceNameNormalizer
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProjectService(
    private val projectRepository: ProjectRepository,
    private val projectAccessService: ProjectAccessService,
    private val projectAccessGuard: ProjectAccessGuard,
    private val permissionCheckService: PermissionCheckService,
    private val scopeQueryRepository: ScopeQueryRepository,
    private val projectManagedServicesExistencePort: ProjectManagedServicesExistencePort,
    private val projectNetworkRemovalPort: ProjectNetworkRemovalPort,
    private val projectDeleteExecutor: ProjectDeleteExecutor,
    private val projectUpdateExecutor: ProjectUpdateExecutor,
    private val projectCreateExecutor: ProjectCreateExecutor
) {
    fun create(
        name: String,
        slug: String,
        description: String? = null,
        ownerPrincipalId: UUID,
    ): Project {
        return try {
            projectCreateExecutor.execute(
                name = name,
                slug = slug,
                description = description,
                ownerPrincipalId = ownerPrincipalId
            )
        } catch (exception: DataIntegrityViolationException) {
            if (projectRepository.existsByNameOrSlug(ResourceNameNormalizer.normalize(name), slug)) {
                throw ProjectAlreadyExistsException("Project with such name/slug already exists")
            }

            throw exception
        }
    }

    fun update(
        projectId: UUID,
        name: String,
        description: String? = null,
        expectedVersion: Long,
        principal: Principal
    ): Project {
        return try {
            projectUpdateExecutor.execute(
                projectId = projectId,
                name = name,
                description = description,
                expectedVersion = expectedVersion,
                principal = principal
            )
        } catch (_: ObjectOptimisticLockingFailureException) {
            projectUpdateExecutor.resolveConflict(
                projectId = projectId,
                name = name,
                description = description
            )
        } catch (exception: DataIntegrityViolationException) {
            if (
                projectRepository.existsByNameAndIdNot(
                    ResourceNameNormalizer.normalize(name),
                    projectId
                )
            ) {
                throw ProjectAlreadyExistsException("Project with such name already exists")
            }

            throw exception
        }
    }

    @Transactional
    fun archive(
        projectId: UUID,
        principal: Principal
    ): Project {
        val project = projectAccessGuard.requireArchivable(projectId, principal)
        val archivedRows = projectRepository.archive(project.id)

        if (archivedRows == 0) {
            when (projectRepository.isArchived(project.id)) {
                true -> Unit
                false -> throw InternalDataIntegrityException(
                    "Project ${project.id} was not archived after an archive update affected no rows"
                )
                null -> throw ProjectNotFoundException(
                    "Project with provided ID couldn't be found"
                )
            }
        }

        return projectRepository.findProjectById(project.id)
            ?: if (archivedRows == 0) {
                throw ProjectNotFoundException("Project with provided ID couldn't be found")
            } else {
                throw InternalDataIntegrityException(
                    "Project ${project.id} disappeared after it was archived"
                )
            }
    }

    fun delete(
        projectId: UUID,
        principal: Principal
    ): Project {
        projectAccessGuard.requireDeletable(projectId, principal)

        if (projectManagedServicesExistencePort.existsByProjectId(projectId)) {
            throw ProjectDeletionBlockedByManagedServicesException(
                "Project $projectId has services attached to it hence can't be deleted"
            )
        }

        val deletedProject = try {
            projectDeleteExecutor.execute(
                projectId = projectId,
                principal = principal
            )
        } catch (exception: DataIntegrityViolationException) {
            if (projectManagedServicesExistencePort.existsByProjectId(projectId)) {
                throw ProjectDeletionBlockedByManagedServicesException(
                    "Project $projectId has services attached to it hence can't be deleted"
                )
            }

            throw exception
        }

        projectNetworkRemovalPort.removeNetwork(
            projectId = deletedProject.id,
            projectSlug = deletedProject.slug
        )

        return deletedProject
    }

    @Transactional
    fun get(
        projectId: UUID,
        principal: Principal
    ): Project {
        return projectAccessGuard.requireReadable(projectId, principal)
    }

    @Transactional
    fun list(
        principal: Principal
    ): List<Project> {
        if (permissionCheckService.hasGlobalPermission(SystemPermissionNames.PROJECT_READ.key, principal.id)) {
            return projectRepository.findAll()
        }

        return projectRepository.findAllByScopeIdIn(
            scopeQueryRepository.effectiveScopeIds(
                principal.id,
                SystemPermissionNames.PROJECT_READ.key,
                ScopeType.PROJECT
            )
        )
    }

    fun createForUser(
        name: String,
        slug: String,
        description: String?,
        user: User
    ): Project {
        return create(
            name = name,
            slug = slug,
            description = description,
            ownerPrincipalId = user.principal.id
        )
    }

    fun updateForUser(
        projectId: UUID,
        name: String,
        description: String?,
        expectedVersion: Long,
        user: User
    ): Project {
        return update(
            projectId = projectId,
            name = name,
            description = description,
            expectedVersion = expectedVersion,
            principal = user.principal
        )
    }

    @Transactional
    fun archiveForUser(
        projectId: UUID,
        user: User
    ): Project {
        return archive(projectId = projectId, principal = user.principal)
    }

    fun deleteForUser(
        projectId: UUID,
        user: User
    ): Project {
        return delete(projectId = projectId, principal = user.principal)
    }
}
