package io.dockistry.platform.projects.application

import io.dockistry.platform.iam.domain.InsufficientPermissionException
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.ProjectNotFoundException
import io.dockistry.platform.projects.infrastructure.ProjectRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProjectAccessGuard(
    private val projectRepository: ProjectRepository,
    private val projectAccessService: ProjectAccessService
) {
    fun requireReadable(projectId: UUID, principal: Principal): Project {
        val project = projectRepository.findProjectById(projectId)
            ?: throw notFound()

        if (!projectAccessService.hasReadPermission(project.scope, principal)) {
            throw notFound()
        }

        return project
    }

    fun requireUpdatable(projectId: UUID, principal: Principal): Project {
        val project = requireReadable(projectId, principal)

        if (!projectAccessService.hasUpdatePermission(project.scope, principal)) {
            throw InsufficientPermissionException(
                "This principal is not allowed to update specified project"
            )
        }

        return project
    }

    fun requireArchivable(projectId: UUID, principal: Principal): Project {
        val project = requireReadable(projectId, principal)

        if (!projectAccessService.hasArchivePermission(project.scope, principal)) {
            throw InsufficientPermissionException(
                "This principal is not allowed to archive specified project"
            )
        }

        return project
    }

    fun requireDeletable(projectId: UUID, principal: Principal): Project {
        val project = requireReadable(projectId, principal)

        if (!projectAccessService.hasDeletePermission(project.scope, principal)) {
            throw InsufficientPermissionException(
                "This principal is not allowed to delete specified project"
            )
        }

        return project
    }

    fun requireMemberManageable(projectId: UUID, principal: Principal): Project {
        val project = requireReadable(projectId, principal)

        if (!projectAccessService.hasMemberManagementPermission(project.scope, principal)) {
            throw InsufficientPermissionException(
                "This principal is not allowed to manage members of specified project"
            )
        }

        return project
    }

    fun requireContainersManageable(projectId: UUID, principal: Principal): Project {
        val project = requireReadable(projectId, principal)
        return requireContainersManageable(project, principal)
    }

    fun requireContainersManageable(project: Project, principal: Principal): Project {
        if (!projectAccessService.hasProjectContainersManagePermission(project.scope, principal)) {
            throw InsufficientPermissionException(
                "This principal is not allowed to manage containers of specified project"
            )
        }

        return project
    }

    fun requireServicesManageable(projectId: UUID, principal: Principal): Project {
        val project = requireReadable(projectId, principal)

        if (!projectAccessService.hasProjectServicesManagePermission(project.scope, principal)) {
            throw InsufficientPermissionException(
                "This principal is not allowed to manage services of specified project"
            )
        }

        return project
    }

    private fun notFound(): ProjectNotFoundException =
        ProjectNotFoundException("Project with provided ID couldn't be found")
}
