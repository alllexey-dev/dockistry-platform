package io.dockistry.platform.services.application

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.DockerNetworkCreateNameConflictException
import io.dockistry.platform.docker.domain.DockerNetworkCreateVerificationFailedException
import io.dockistry.platform.docker.domain.DockerNetworkOwnershipConflictException
import io.dockistry.platform.docker.domain.model.DockerNetworkRef
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProjectNetworkService(
    private val dockerAgentClient: DockerAgentClient
) {
    fun createNetwork(projectId: UUID): String {
        val name = projectNetworkName(projectId)
        try {
            dockerAgentClient.createNetwork(
                name = name,
                labels = DockerLabels.forProject(projectId)
            )
        } catch (_: DockerNetworkCreateNameConflictException) {
            // Another deployment may have created the shared project network first.
        }

        return findNetwork(projectId)
            ?: throw DockerNetworkCreateVerificationFailedException(name)
    }

    fun findNetwork(projectId: UUID): String? {
        val network = dockerAgentClient.findNetworkByName(
            projectNetworkName(projectId)
        ) ?: return null

        if (!network.hasLabels(DockerLabels.forProject(projectId))) {
            throw DockerNetworkOwnershipConflictException()
        }
        return network.name
    }

    fun projectNetworkName(projectId: UUID) = "dockistry_$projectId"

    private fun DockerNetworkRef.hasLabels(expected: Map<String, String>): Boolean {
        return expected.all { (name, value) -> labels[name] == value }
    }
}
