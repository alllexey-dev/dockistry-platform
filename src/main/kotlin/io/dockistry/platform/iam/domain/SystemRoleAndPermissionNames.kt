package io.dockistry.platform.iam.domain

enum class SystemRoleNames(
    val key: String,
    val description: String
) {
    PROJECT_ADMIN(
        "project_admin",
        "System role for full project management, including creation, reading, " +
            "updates, and deletion or archival. Effective access is limited by " +
            "the scope used in the role binding."
    ),
    PROJECT_OPERATOR(
        "project_operator",
        "System role for day-to-day project operation: viewing project data and " +
            "changing project configuration. Effective access is limited by the " +
            "scope used in the role binding."
    ),
    PROJECT_VIEWER(
        "project_viewer",
        "System role for read-only access to project data. Effective access is " +
            "limited by the scope used in the role binding."
    );

    val permissionNames: Set<SystemPermissionNames>
        get() = when (this) {
            PROJECT_ADMIN -> SystemPermissionNames.entries.toSet()
            PROJECT_OPERATOR -> setOf(
                SystemPermissionNames.PROJECT_READ,
                SystemPermissionNames.PROJECT_UPDATE,
                SystemPermissionNames.PROJECT_SERVICES_MANAGE
            )

            PROJECT_VIEWER -> setOf(SystemPermissionNames.PROJECT_READ)
        }
}

enum class SystemPermissionNames(
    val key: String,
    val description: String
) {
    PROJECT_CREATE(
        "project.create",
        "Allows creating projects or project contents depending on the scope " +
            "where this permission is granted."
    ),
    PROJECT_READ(
        "project.read",
        "Allows viewing project metadata and project-level resources in the " +
            "granted scope."
    ),
    PROJECT_UPDATE(
        "project.update",
        "Allows changing project settings, metadata, and operational " +
            "configuration in the granted scope."
    ),
    PROJECT_DELETE(
        "project.delete",
        "Allows deleting projects and their contents in the granted scope."
    ),
    PROJECT_ARCHIVE(
        "project.archive",
        "Allows archiving project(s) depending on the granted scope."
    ),
    PROJECT_MEMBERS_MANAGE(
        "project.members.manage",
        "Allows adding and removing project member role bindings in the " +
            "granted scope."
    ),
    PROJECT_SERVICES_MANAGE(
        "projects.services.manage",
        "Allows creating, updating, and deleting managed services in the " +
            "granted project scope."
    ),
    DOCKER_GENERIC_CONTAINERS_MANAGE(
        "docker.generic-containers.manage",
        "Allows viewing and managing non-Dockistry containers on the Docker " +
            "host."
    ),
    DOCKER_IMAGES_READ(
        "docker.images.read",
        "Allows viewing Docker image metadata through the Docker agent in the " +
            "granted scope."
    ),
    DOCKER_IMAGES_MANAGE(
        "docker.images.manage",
        "Allows pulling and removing Docker images through the Docker agent in " +
            "the granted scope."
    ),
    SECRETS_DELEGATE(
        "project.secrets.delegate",
        "Allows delegating secrets access to project in the granted scope."
    ),
    SECRETS_USE(
        "project.secrets.use",
        "Allows using project secrets in the granted scope."
    ),
    SECRETS_FORCE_REVOKE(
        "project.secrets.force-revoke",
        "Allows forcibly revoking access to project secrets in the granted scope."
    );
}
