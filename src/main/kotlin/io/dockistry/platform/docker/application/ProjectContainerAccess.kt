package io.dockistry.platform.docker.application

import io.dockistry.platform.docker.domain.model.DockerContainerDetails
import io.dockistry.platform.projects.domain.Project

data class ProjectContainerAccess(
    val project: Project,
    val container: DockerContainerDetails
)
