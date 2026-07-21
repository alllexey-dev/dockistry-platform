package io.dockistry.platform.projects.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.infrastructure.ProjectRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProjectDeleteExecutor(
    private val projectAccessGuard: ProjectAccessGuard,
    private val projectRepository: ProjectRepository
) {
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun execute(projectId: UUID, principal: Principal): Project {
        val project = projectAccessGuard.requireDeletable(projectId, principal)
        projectRepository.delete(project)
        projectRepository.flush()
        return project
    }
}
