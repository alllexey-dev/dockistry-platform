package io.dockistry.platform.projects.application

import io.dockistry.platform.iam.application.PermissionCheckService
import io.dockistry.platform.iam.domain.InsufficientPermissionException
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.iam.domain.SystemRoleNames
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.iam.infrastructure.PrincipalRepository
import io.dockistry.platform.iam.infrastructure.RoleBindingRepository
import io.dockistry.platform.iam.infrastructure.RoleRepository
import io.dockistry.platform.iam.infrastructure.ScopeQueryRepository
import io.dockistry.platform.projects.application.ports.ProjectManagedServicesExistencePort
import io.dockistry.platform.projects.domain.InvalidProjectSlugException
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.ProjectAlreadyExistsException
import io.dockistry.platform.projects.domain.ProjectDeletionBlockedByManagedServicesException
import io.dockistry.platform.projects.domain.ProjectStatus
import io.dockistry.platform.projects.infrastructure.ProjectRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class ProjectService(
    private val clock: Clock,
    private val projectRepository: ProjectRepository,
    private val projectAccessService: ProjectAccessService,
    private val projectAccessGuard: ProjectAccessGuard,
    private val roleRepository: RoleRepository,
    private val principalRepository: PrincipalRepository,
    private val permissionCheckService: PermissionCheckService,
    private val scopeQueryRepository: ScopeQueryRepository,
    private val roleBindingRepository: RoleBindingRepository,
    private val projectManagedServicesExistencePort: ProjectManagedServicesExistencePort,
    private val projectDeleteExecutor: ProjectDeleteExecutor
) {
    @Transactional
    fun create(
        name: String,
        slug: String,
        description: String? = null,
        ownerPrincipalId: UUID,
    ): Project {
        val ownerPrincipal = principalRepository.findPrincipalById(ownerPrincipalId)
            ?: throw InternalDataIntegrityException(
                "Principal $ownerPrincipalId was passed for project creation, " +
                    "but it couldn't be found in the DB"
            )

        val scope = Scope(ScopeType.PROJECT)

        if (!projectAccessService.hasCreatePermission(ownerPrincipal)) {
            throw InsufficientPermissionException("This principal is not allowed to create projects")
        }

        val project = Project(
            name = name,
            slug = slug,
            description = description,
            ownerPrincipal = ownerPrincipal,
            createdAt = clock.instant(),
            scope = scope
        )

        val systemAdminRole = roleRepository.findByNameAndIsSystemTrue(SystemRoleNames.PROJECT_ADMIN)
            ?: throw InternalDataIntegrityException("Basic system role project_admin couldn't be found")

        val savedProject = try {
            projectRepository.saveAndFlush(project)
        } catch (_: ConstraintViolationException) {
            throw InvalidProjectSlugException("Project slug '$slug' is invalid")
        } catch (_: DataIntegrityViolationException) {
            throw ProjectAlreadyExistsException("Project with such name/slug already exists")
        }

        roleBindingRepository.saveAndFlush(
            RoleBinding(ownerPrincipal, savedProject.scope, systemAdminRole)
        )

        return savedProject
    }

    @Transactional
    fun update(
        projectId: UUID,
        name: String,
        slug: String,
        description: String? = null,
        principal: Principal
    ): Project {
        val project = projectAccessGuard.requireUpdatable(projectId, principal)

        project.name = name
        project.slug = slug
        project.description = description

        try {
            return projectRepository.saveAndFlush(project)
        } catch (_: ConstraintViolationException) {
            throw InvalidProjectSlugException("Project slug '$slug' is invalid")
        } catch (_: DataIntegrityViolationException) {
            throw ProjectAlreadyExistsException("Project with such name/slug already exists")
        }
    }

    @Transactional
    fun archive(
        projectId: UUID,
        principal: Principal
    ): Project {
        val project = projectAccessGuard.requireArchivable(projectId, principal)

        project.status = ProjectStatus.ARCHIVED

        return project
    }

    fun delete(
        projectId: UUID,
        principal: Principal
    ): Project {
        return try {
            projectDeleteExecutor.execute(
                projectId = projectId,
                principal = principal
            )
        } catch (exception: DataIntegrityViolationException) {
            if (projectManagedServicesExistencePort.existsByProjectId(projectId)) {
                throw ProjectDeletionBlockedByManagedServicesException("Project $projectId has services attached to it hence can't be deleted")
            }

            throw exception
        }
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
        if (permissionCheckService.hasGlobalPermission(SystemPermissionNames.PROJECT_READ, principal.id)) {
            return projectRepository.findAll()
        }

        return projectRepository.findAllByScopeIdIn(
            scopeQueryRepository.effectiveScopeIds(
                principal.id,
                SystemPermissionNames.PROJECT_READ,
                ScopeType.PROJECT
            )
        )
    }

    @Transactional
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

    @Transactional
    fun updateForUser(
        projectId: UUID,
        name: String,
        slug: String,
        description: String?,
        user: User
    ): Project {
        return update(
            projectId = projectId,
            name = name,
            slug = slug,
            description = description,
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

    @Transactional
    fun deleteForUser(
        projectId: UUID,
        user: User
    ): Project {
        return delete(projectId = projectId, principal = user.principal)
    }
}
