package io.dockistry.platform.docker.domain

import java.util.UUID

object DockerLabels {
    private const val NAMESPACE = "io.dockistry."

    const val MANAGED = "io.dockistry.managed"
    const val PROJECT_ID = "io.dockistry.project-id"
    const val SERVICE_ID = "io.dockistry.service-id"
    const val DEPLOYMENT_ID = "io.dockistry.deployment-id"

    fun containsDockistryLabel(labels: Map<String, String>): Boolean =
        labels.keys.any { it.startsWith(NAMESPACE) }

    fun forProject(projectId: UUID): Map<String, String> =
        mapOf(
            MANAGED to "true",
            PROJECT_ID to projectId.toString()
        )

    fun forService(
        projectId: UUID,
        serviceId: UUID
    ): Map<String, String> =
        mapOf(
            MANAGED to "true",
            PROJECT_ID to projectId.toString(),
            SERVICE_ID to serviceId.toString()
        )

    fun forDeployment(
        projectId: UUID,
        serviceId: UUID,
        deploymentId: UUID
    ): Map<String, String> =
        mapOf(
            MANAGED to "true",
            PROJECT_ID to projectId.toString(),
            SERVICE_ID to serviceId.toString(),
            DEPLOYMENT_ID to deploymentId.toString()
        )
}
