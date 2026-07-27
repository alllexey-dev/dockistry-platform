package io.dockistry.platform.iam.domain

object SystemRoleNames {
    const val PROJECT_ADMIN = "project_admin"
    const val PROJECT_OPERATOR = "project_operator"
    const val PROJECT_VIEWER = "project_viewer"
}

object SystemPermissionNames {
    const val PROJECT_CREATE = "project.create"
    const val PROJECT_READ = "project.read"
    const val PROJECT_UPDATE = "project.update"
    const val PROJECT_DELETE = "project.delete"
    const val PROJECT_ARCHIVE = "project.archive"
    const val PROJECT_MEMBERS_MANAGE = "project.members.manage"
    const val PROJECT_SERVICES_MANAGE = "projects.services.manage"
    const val DOCKER_GENERIC_CONTAINERS_MANAGE = "docker.generic-containers.manage"
    const val DOCKER_IMAGES_READ = "docker.images.read"
    const val DOCKER_IMAGES_MANAGE = "docker.images.manage"
}
