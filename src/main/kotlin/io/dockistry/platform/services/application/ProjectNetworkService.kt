package io.dockistry.platform.services.application

import io.dockistry.platform.docker.application.ContainerService
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.DockerNetworkCreateNameConflictException
import io.dockistry.platform.docker.domain.DockerNetworkCreateVerificationFailedException
import io.dockistry.platform.docker.domain.DockerNetworkNotFoundException
import io.dockistry.platform.docker.domain.DockerNetworkOwnershipConflictException
import io.dockistry.platform.docker.domain.model.DockerNetworkRef
import io.dockistry.platform.projects.application.ports.ProjectNetworkRemovalPort
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProjectNetworkService(
    private val containerService: ContainerService
) : ProjectNetworkRemovalPort {
    fun createNetwork(projectId: UUID, projectSlug: String): String {
        val name = projectNetworkName(projectSlug)
        try {
            containerService.createNetwork(
                name = name,
                labels = DockerLabels.forProject(projectId)
            )
        } catch (_: DockerNetworkCreateNameConflictException) {
            // Another deployment may have created the shared project network first.
        }

        return findNetwork(projectId, projectSlug)
            ?: throw DockerNetworkCreateVerificationFailedException(name)
    }

    fun findNetwork(projectId: UUID, projectSlug: String): String? {
        return findNetworkRef(projectId, projectSlug)?.name
    }

    override fun removeNetwork(projectId: UUID, projectSlug: String) {
        val network = findNetworkRef(projectId, projectSlug) ?: return

        try {
            containerService.removeNetwork(network.id)
        } catch (_: DockerNetworkNotFoundException) {
            // A concurrent removal already reached the target state.
        }
    }

    fun projectNetworkName(projectSlug: String) = "dockistry_$projectSlug"

    private fun findNetworkRef(
        projectId: UUID,
        projectSlug: String
    ): DockerNetworkRef? {
        val network = containerService.findNetworkByName(
            projectNetworkName(projectSlug)
        ) ?: return null

        if (!network.hasLabels(DockerLabels.forProject(projectId))) {
            throw DockerNetworkOwnershipConflictException()
        }
        return network
    }

    private fun DockerNetworkRef.hasLabels(expected: Map<String, String>): Boolean {
        return expected.all { (name, value) -> labels[name] == value }
    }
}
