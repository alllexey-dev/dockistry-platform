package io.dockistry.platform.services.application

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.DockerVolumeCreateNameConflictException
import io.dockistry.platform.docker.domain.DockerVolumeCreateVerificationFailedException
import io.dockistry.platform.docker.domain.DockerVolumeNotFoundException
import io.dockistry.platform.docker.domain.DockerVolumeOwnershipConflictException
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.docker.domain.model.DockerVolumeRef
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProjectVolumeService(private val dockerAgentClient: DockerAgentClient) {
    fun createVolume(
        projectId: UUID,
        serviceId: UUID,
        deploymentId: UUID,
        serviceName: String,
        volumeName: String
    ): String {
        val name = fullVolumeName(projectId, serviceName, volumeName)

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
            serviceId = serviceId,
            serviceName = serviceName,
            volumeName = volumeName
        ) ?: throw DockerVolumeCreateVerificationFailedException(name)
    }

    fun findVolume(
        projectId: UUID,
        serviceId: UUID,
        serviceName: String,
        volumeName: String
    ): String? {
        val name = fullVolumeName(projectId, serviceName, volumeName)
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
            try {
                dockerAgentClient.removeVolume(it.name)
            } catch (_: DockerVolumeNotFoundException) {
                // A concurrent Docker-side removal already reached the target state.
            }
        }
    }

    fun fullVolumeName(
        projectId: UUID,
        serviceName: String,
        volumeName: String
    ) = "dockistry_${projectId}_${serviceName}_$volumeName"

    private fun DockerVolumeRef.hasLabels(expected: Map<String, String>): Boolean {
        return expected.all { (name, value) -> labels[name] == value }
    }
}
