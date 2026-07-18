package io.dockistry.platform.services.application

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerContainerCreateNameConflictException
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.model.DockerContainerCreateCommand
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerImageRef
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.docker.domain.model.DockerPortBinding
import io.dockistry.platform.docker.domain.model.DockerPortProtocol
import io.dockistry.platform.docker.domain.model.DockerRestartPolicy
import io.dockistry.platform.docker.domain.model.DockerVolumeBinding
import io.dockistry.platform.services.application.dto.ManagedServiceDeploymentPlan
import io.dockistry.platform.services.domain.ContainerNameConflictDeletionRequiredException
import io.dockistry.platform.services.domain.ContainerNameConflictSupportRequiredException
import io.dockistry.platform.services.domain.ManagedServiceDeploymentStatus
import io.dockistry.platform.services.domain.ManagedServiceImageRef
import io.dockistry.platform.services.domain.ServiceEndpointMode
import io.dockistry.platform.services.domain.ServiceEndpointProtocol
import io.dockistry.platform.services.infrastructure.ManagedServiceDeploymentRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Service
class ManagedServiceDeployExecutor(
    private val deploymentLifecycleService: ManagedServiceDeploymentLifecycleService,
    private val projectNetworkService: ProjectNetworkService,
    private val projectVolumeService: ProjectVolumeService,
    private val deploymentRepository: ManagedServiceDeploymentRepository,
    private val dockerAgentClient: DockerAgentClient
) {

    companion object {
        private val logger: Logger =
            LoggerFactory.getLogger(ManagedServiceDeployExecutor::class.java)
    }

    // TODO: support for deploy cancellation by user
    fun deploy(serviceId: UUID) {
        val plan = deploymentLifecycleService.begin(serviceId)

        val containerId = try {
            deployToDocker(plan)
        } catch (_: DockerContainerCreateNameConflictException) {
            val conflictException = properConflictException(plan)
            deploymentLifecycleService.fail(
                deploymentId = plan.deploymentId,
                errorMessage = conflictException.message
            )
            throw conflictException
        } catch (exception: RuntimeException) {
            deploymentLifecycleService.fail(
                deploymentId = plan.deploymentId,
                errorMessage = exception.message
            )
            throw exception
        }

        deploymentLifecycleService.finish(plan.deploymentId, containerId)
    }

    private fun deployToDocker(plan: ManagedServiceDeploymentPlan): String {
        val command = createCommand(plan)

        return try {
            dockerAgentClient.createContainer(command)
        } catch (exception: DockerContainerCreateNameConflictException) {
            if (!handleConflict(plan)) {
                throw exception
            }

            // If conflicting container was automatically deleted, try again once
            dockerAgentClient.createContainer(command)
        }
    }

    private fun createCommand(
        plan: ManagedServiceDeploymentPlan
    ): DockerContainerCreateCommand {
        val networkName = ensureNetworkAndGetName(plan)
        val bindings = ensureAndMapVolumes(plan)

        val imageRef = plan.snapshot.imageRef.toDockerImageRef()
        dockerAgentClient.pullImage(imageRef)

        return DockerContainerCreateCommand(
            name = containerName(plan),
            image = imageRef,
            env = plan.snapshot.env,
            labels = DockerLabels.forDeployment(
                projectId = plan.projectId,
                serviceId = plan.serviceId,
                deploymentId = plan.deploymentId
            ),
            command = plan.snapshot.command,
            args = plan.snapshot.args,
            networkName = networkName,
            networkAliases = listOf(plan.serviceName),
            portBindings = plan.snapshot.endpoints
                .filter { it.mode == ServiceEndpointMode.DIRECT }
                .map { endpoint ->
                    DockerPortBinding(
                        containerPort = endpoint.port,
                        hostPort = endpoint.externalPort
                            ?: throw InternalDataIntegrityException(
                                "Validated config snapshot has incorrect DIRECT endpoint: " +
                                        "no external port"
                            ),
                        protocol = when (endpoint.protocol) {
                            ServiceEndpointProtocol.TCP -> DockerPortProtocol.TCP
                            ServiceEndpointProtocol.UDP -> DockerPortProtocol.UDP
                        }
                    )
                },
            volumeBindings = bindings,
            restartPolicy = DockerRestartPolicy(
                type = plan.snapshot.restartPolicy,
                maxRetries = plan.snapshot.restartMaxRetries
            ),
            memoryLimitBytes = plan.snapshot.memoryLimitBytes,
            cpuLimitNanoCpus = plan.snapshot.cpuLimitNanoCpus
        )
    }

    private fun properConflictException(plan: ManagedServiceDeploymentPlan): RuntimeException {
        return if (getConflictingContainer(plan) == null) {
            ContainerNameConflictSupportRequiredException()
        } else {
            ContainerNameConflictDeletionRequiredException()
        }
    }

    private fun handleConflict(plan: ManagedServiceDeploymentPlan): Boolean {
        val containerDetails = getConflictingContainer(plan)?.takeIf {
            it.labels[DockerLabels.DEPLOYMENT_ID] != null
        }

        val deployment = containerDetails?.labels[DockerLabels.DEPLOYMENT_ID]?.let {
            try {
                val deploymentId = UUID.fromString(it)
                deploymentRepository.findById(deploymentId).getOrNull()
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        if (deployment?.status == ManagedServiceDeploymentStatus.FAILED) {
            try {
                dockerAgentClient.deleteContainer(containerDetails.id)
                return true
            } catch (exception: DockerContainerNotFoundException) {
                logger.error(
                    "Container ${containerDetails.id} not found while handling " +
                            "container creation name conflict, even though it was just found " +
                            "through dockerAgentClient.listContainers",
                    exception
                )
            }
        }
        return false
    }

    private fun getConflictingContainer(
        plan: ManagedServiceDeploymentPlan
    ): DockerContainerSummary? {
        return dockerAgentClient.listContainers(
            DockerLabelsFilter(
                labels = DockerLabels.forService(plan.projectId, plan.serviceId)
            )
        ).firstOrNull {
            it.name == containerName(plan)
        }
    }

    private fun ensureNetworkAndGetName(plan: ManagedServiceDeploymentPlan): String {
        return projectNetworkService.findNetwork(plan.projectSlug)
            ?: projectNetworkService.createNetwork(
                projectId = plan.projectId,
                projectSlug = plan.projectSlug
            )
    }

    private fun ensureAndMapVolumes(
        plan: ManagedServiceDeploymentPlan
    ): List<DockerVolumeBinding> {
        return plan.snapshot.volumes.map { volumeMount ->
            DockerVolumeBinding(
                volumeName = ensureVolume(plan, volumeMount.name),
                mountPath = volumeMount.mountPath,
                readOnly = volumeMount.readOnly
            )
        }
    }

    private fun ensureVolume(
        plan: ManagedServiceDeploymentPlan,
        volumeName: String
    ): String {
        return projectVolumeService.findVolume(
            projectSlug = plan.projectSlug,
            serviceName = plan.serviceName,
            volumeName = volumeName
        ) ?: projectVolumeService.createVolume(
            projectId = plan.projectId,
            projectSlug = plan.projectSlug,
            serviceId = plan.serviceId,
            serviceName = plan.serviceName,
            volumeName = volumeName
        )
    }

    private fun ManagedServiceImageRef.toDockerImageRef(): DockerImageRef {
        return DockerImageRef(
            repository = repository,
            tag = tag
        )
    }

    private fun containerName(plan: ManagedServiceDeploymentPlan): String =
        "dockistry_${plan.projectSlug}_${plan.serviceName}"
}
