package io.dockistry.platform.projects.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.infrastructure.ScopeRepository
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.ProjectNotFoundException
import io.dockistry.platform.projects.infrastructure.ProjectRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProjectDeleteExecutor(
    private val projectAccessGuard: ProjectAccessGuard,
    private val projectRepository: ProjectRepository,
    private val scopeRepository: ScopeRepository
) {
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun execute(projectId: UUID, principal: Principal): Project {
        val project = projectAccessGuard.requireDeletable(projectId, principal)
        val scopeId = project.scope.id

        if (projectRepository.deleteIgnoringVersion(projectId) == 0) {
            throw ProjectNotFoundException("Project with provided ID couldn't be found")
        }

        scopeRepository.deleteById(scopeId)

        return project
    }
}
