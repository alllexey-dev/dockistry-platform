package io.dockistry.platform.services.application

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.DockerVolumeCreateNameConflictException
import io.dockistry.platform.docker.domain.DockerVolumeCreateVerificationFailedException
import io.dockistry.platform.docker.domain.DockerVolumeOwnershipConflictException
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.docker.domain.model.DockerVolumeRef
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProjectVolumeService(private val dockerAgentClient: DockerAgentClient) {
    fun createVolume(
        projectId: UUID,
        projectSlug: String,
        serviceId: UUID,
        deploymentId: UUID,
        serviceName: String,
        volumeName: String
    ): String {
        val name = fullVolumeName(projectSlug, serviceName, volumeName)

        try {
            dockerAgentClient.createVolume(
                name = name,
                labels = DockerLabels.forDeployment(
                    projectId = projectId,
                    serviceId = serviceId,
                    deploymentId = deploymentId
                )
            )
        } catch (_: DockerVolumeCreateNameConflictException) {
            // Re-read and validate the winner of a concurrent name race below.
        }

        return findVolume(
            projectId = projectId,
            projectSlug = projectSlug,
            serviceId = serviceId,
            serviceName = serviceName,
            volumeName = volumeName
        ) ?: throw DockerVolumeCreateVerificationFailedException(name)
    }

    fun findVolume(
        projectId: UUID,
        projectSlug: String,
        serviceId: UUID,
        serviceName: String,
        volumeName: String
    ): String? {
        val name = fullVolumeName(projectSlug, serviceName, volumeName)
        val volume = dockerAgentClient.findVolumeByName(name) ?: return null

        if (!volume.hasLabels(DockerLabels.forService(projectId, serviceId))) {
            throw DockerVolumeOwnershipConflictException()
        }
        return volume.name
    }

    fun removeServiceVolumesByLabels(
        projectId: UUID,
        serviceId: UUID
    ) {
        dockerAgentClient.listVolumes(
            DockerLabelsFilter(
                DockerLabels.forService(
                    projectId = projectId,
                    serviceId = serviceId
                )
            )
        ).forEach {
            dockerAgentClient.removeVolume(it.name)
        }
    }

    fun fullVolumeName(
        projectSlug: String,
        serviceName: String,
        volumeName: String
    ) = "dockistry_${projectSlug}_${serviceName}_$volumeName"

    private fun DockerVolumeRef.hasLabels(expected: Map<String, String>): Boolean {
        return expected.all { (name, value) -> labels[name] == value }
    }
}
