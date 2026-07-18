package io.dockistry.platform.services.application

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerLabels
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProjectVolumeService(private val dockerAgentClient: DockerAgentClient) {
    fun createVolume(
        projectId: UUID,
        projectSlug: String,
        serviceId: UUID,
        serviceName: String,
        volumeName: String
    ): String {
        val name = fullVolumeName(projectSlug, serviceName, volumeName)

        dockerAgentClient.createVolume(
            name = name,
            labels = DockerLabels.forService(projectId, serviceId)
        )
        return name
    }

    fun findVolume(
        projectSlug: String,
        serviceName: String,
        volumeName: String
    ): String? {
        val name = fullVolumeName(projectSlug, serviceName, volumeName)
        return if (dockerAgentClient.existsVolumeByName(name)) name else null
    }

    fun fullVolumeName(
        projectSlug: String,
        serviceName: String,
        volumeName: String
    ) = "dockistry_${projectSlug}_${serviceName}_$volumeName"
}
