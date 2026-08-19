package io.dockistry.platform.projects.application

import io.dockistry.platform.projects.infrastructure.ProjectRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProjectExistenceService(private val projectRepository: ProjectRepository) {
    fun exists(projectId: UUID): Boolean {
        return projectRepository.existsById(projectId)
    }
}
