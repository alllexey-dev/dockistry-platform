package io.dockistry.platform.projects.application

import io.dockistry.platform.iam.domain.InsufficientPermissionException
import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.domain.SystemRoleNames
import io.dockistry.platform.iam.infrastructure.PrincipalRepository
import io.dockistry.platform.iam.infrastructure.RoleBindingRepository
import io.dockistry.platform.iam.infrastructure.RoleRepository
import io.dockistry.platform.projects.domain.InvalidProjectSlugException
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.ProjectAlreadyExistsException
import io.dockistry.platform.projects.infrastructure.ProjectRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import jakarta.validation.ConstraintViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class ProjectCreateExecutor(
    private val clock: Clock,
    private val projectRepository: ProjectRepository,
    private val projectAccessService: ProjectAccessService,
    private val roleRepository: RoleRepository,
    private val principalRepository: PrincipalRepository,
    private val roleBindingRepository: RoleBindingRepository
) {
    /*
     * Uses REQUIRED rather than REQUIRES_NEW like the update/delete executors: callers
     * that already run in a transaction (e.g. @Transactional tests with uncommitted
     * fixtures) must see their own data. A unique-constraint race is then only
     * re-checked correctly by ProjectService.create when there is no outer transaction.
     */
    @Transactional
    fun execute(
        name: String,
        slug: String,
        description: String?,
        ownerPrincipalId: UUID
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

        if (projectRepository.existsByNameOrSlug(project.name, project.slug)) {
            throw ProjectAlreadyExistsException("Project with such name/slug already exists")
        }

        val systemAdminRole = roleRepository.findByNameAndIsSystemTrue(SystemRoleNames.PROJECT_ADMIN.key)
            ?: throw InternalDataIntegrityException("Basic system role project_admin couldn't be found")

        val savedProject = try {
            projectRepository.saveAndFlush(project)
        } catch (_: ConstraintViolationException) {
            throw InvalidProjectSlugException("Project slug '$slug' is invalid")
        }

        roleBindingRepository.saveAndFlush(
            RoleBinding(ownerPrincipal, savedProject.scope, systemAdminRole)
        )

        return savedProject
    }
}
