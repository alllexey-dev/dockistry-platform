package io.dockistry.platform.projects.application

import io.dockistry.platform.iam.application.PermissionCheckService
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.iam.domain.SystemRoleNames
import io.dockistry.platform.iam.infrastructure.PrincipalRepository
import io.dockistry.platform.iam.infrastructure.RoleRepository
import io.dockistry.platform.projects.domain.InsufficientPermissionException
import io.dockistry.platform.projects.domain.InvalidProjectSlugException
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.ProjectAlreadyExistsException
import io.dockistry.platform.projects.domain.ProjectNotFoundException
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
    private val roleRepository: RoleRepository,
    private val principalRepository: PrincipalRepository,
    private val permissionCheckService: PermissionCheckService
) {
    @Transactional
    fun create(
        name: String,
        slug: String,
        description: String? = null,
        ownerPrincipalId: UUID,
    ): Project {
        val ownerPrincipal = principalRepository.findPrincipalById(ownerPrincipalId)
            ?: throw InternalDataIntegrityException("Principal $ownerPrincipalId was passed for project creation, but it couldn't be found in the DB")

        val scope = Scope(ScopeType.PROJECT)

        if (!projectAccessService.canCreate(ownerPrincipal)) {
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

        val systemAdminRole = roleRepository.findByName(SystemRoleNames.PROJECT_ADMIN)
            ?: throw InternalDataIntegrityException("Basic system role project_admin couldn't be found")

        ownerPrincipal.roleBindings.add(
            RoleBinding(ownerPrincipal, scope, systemAdminRole)
        )

        try {
            return projectRepository.saveAndFlush(project)
        } catch (_: ConstraintViolationException) {
            throw InvalidProjectSlugException("Project slug '$slug' is invalid")
        } catch (_: DataIntegrityViolationException) {
            throw ProjectAlreadyExistsException("Project with such name/slug already exists")
        }
    }

    @Transactional
    fun update(
        projectId: UUID,
        name: String,
        slug: String,
        description: String? = null,
        principal: Principal
    ): Project {
        val project = projectRepository.findProjectById(projectId)
            ?: throw ProjectNotFoundException("Project with provided ID couldn't be found")

        if (!projectAccessService.canUpdate(project.scope, principal)) {
            throw InsufficientPermissionException("This principal is not allowed to view/edit specified project")
        }

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
        val project = projectRepository.findProjectById(projectId)
            ?: throw ProjectNotFoundException("Project with provided ID couldn't be found")

        if (!projectAccessService.canArchive(project.scope, principal)) {
            throw InsufficientPermissionException("This principal is not allowed to view/edit specified project")
        }

        project.status = ProjectStatus.ARCHIVED

        return project
    }

    @Transactional
    fun delete(
        projectId: UUID,
        principal: Principal
    ): Project {
        val project = projectRepository.findProjectById(projectId)
            ?: throw ProjectNotFoundException("Project with provided ID couldn't be found")

        if (!projectAccessService.canDelete(project.scope, principal)) {
            throw InsufficientPermissionException("This principal is not allowed to delete specified project")
        }

        project.scope.roleBindings.toList().forEach { binding ->
            binding.subjectPrincipal.roleBindings.remove(binding)
            binding.role.roleBindings.remove(binding)
            project.scope.roleBindings.remove(binding)
        }
        projectRepository.delete(project)
        return project
    }

    @Transactional
    fun get(
        projectId: UUID,
        principal: Principal
    ): Project {
        val project = projectRepository.findProjectById(projectId)
            ?: throw ProjectNotFoundException("Project with provided ID couldn't be found")

        if (!projectAccessService.canRead(project.scope, principal)) {
            throw InsufficientPermissionException("This principal is not allowed to access specified project")
        }

        return project
    }

    @Transactional
    fun list(
        principal: Principal
    ): List<Project> {
        if (permissionCheckService.hasGlobalPermission(SystemPermissionNames.PROJECT_READ, principal.id)) {
            return projectRepository.findAll()
        }

        return principal.roleBindings.filter { binding ->
            binding.scope.scopeType == ScopeType.PROJECT
                    && projectAccessService.canRead(binding.scope, principal)
        }
            .distinctBy { binding -> binding.scope.id }
            .map { binding ->
                projectRepository.findByScope(binding.scope)
                    ?: throw InternalDataIntegrityException("No project could be found by Scope ${binding.scope.id} with ScopeType = PROJECT")
            }
            .toList()
    }
}
