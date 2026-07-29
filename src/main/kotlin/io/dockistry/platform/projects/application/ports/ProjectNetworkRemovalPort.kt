package io.dockistry.platform.projects.application.ports

import java.util.UUID

interface ProjectNetworkRemovalPort {
    fun removeNetwork(projectId: UUID, projectSlug: String)
}
