package io.dockistry.platform.projects.application.ports

import java.util.UUID

interface ProjectManagedServicesExistencePort {
    fun existsByProjectId(projectId: UUID): Boolean
}
