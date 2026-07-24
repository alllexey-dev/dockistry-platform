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
import io.dockistry.platform.services.domain.ManagedServiceDeploymentCanceled
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
    private val deploymentCleanupService: ManagedServiceDeploymentCleanupService,
    private val projectNetworkService: ProjectNetworkService,
    private val projectVolumeService: ProjectVolumeService,
    private val deploymentRepository: ManagedServiceDeploymentRepository,
    private val dockerAgentClient: DockerAgentClient,
    private val deploymentOperationGate: ManagedServiceDeploymentOperationGate
) {

    companion object {
        private val logger: Logger =
            LoggerFactory.getLogger(ManagedServiceDeployExecutor::class.java)

        internal fun containerName(projectId: UUID, serviceName: String): String =
            "dockistry_${projectId}_$serviceName"
    }

    fun deploy(serviceId: UUID) {
        val plan = deploymentLifecycleService.begin(serviceId)

        val command = try {
            createCommand(plan)
        } catch (exception: RuntimeException) {
            if (handleDeploymentFailure(plan, exception)) {
                return throwIfDeploymentCanceled(plan)
            }
            throw exception
        }

        val outcome: DeployOutcome = deploymentOperationGate.withLock(plan.deploymentId) {
            try {
                val containerId = deployToDocker(plan, command)

                when (
                    deploymentLifecycleService.finish(
                        deploymentId = plan.deploymentId,
                        containerId = containerId
                    )
                ) {
                    ManagedServiceDeploymentFinalizationResult.APPLIED ->
                        DeployOutcome.Finished

                    ManagedServiceDeploymentFinalizationResult.CANCELLATION_REQUESTED ->
                        DeployOutcome.CancellationRequested
                }
            } catch (_: DockerContainerCreateNameConflictException) {
                recordFailWithOutcome(plan, properConflictException(plan))
            } catch (exception: RuntimeException) {
                recordFailWithOutcome(plan, exception)
            }
        }

        when (outcome) {
            DeployOutcome.Finished -> Unit
            DeployOutcome.CancellationRequested -> deploymentCleanupService.cleanup(
                projectId = plan.projectId,
                serviceId = plan.serviceId,
                deploymentId = plan.deploymentId
            )

            is DeployOutcome.Failed -> throw outcome.exception
        }
        throwIfDeploymentCanceled(plan)
    }

    private fun deployToDocker(
        plan: ManagedServiceDeploymentPlan,
        command: DockerContainerCreateCommand
    ): String {
        return try {
            throwIfDeploymentCanceled(plan)
            val containerId = dockerAgentClient.createContainer(command)
            dockerAgentClient.startContainer(containerId)

            containerId
        } catch (exception: DockerContainerCreateNameConflictException) {
            throwIfDeploymentCanceled(plan)

            if (!handleConflict(plan)) {
                throw exception
            }

            // If conflicting container was automatically deleted, try again once
            throwIfDeploymentCanceled(plan)
            val containerId = dockerAgentClient.createContainer(command)
            dockerAgentClient.startContainer(containerId)

            containerId
        }
    }

    private fun throwIfDeploymentCanceled(plan: ManagedServiceDeploymentPlan) {
        if (deploymentLifecycleService.isCancellationRequested(plan.deploymentId)) {
            throw ManagedServiceDeploymentCanceled("This deployment was canceled")
        }
    }

    private fun recordFailWithOutcome(
        plan: ManagedServiceDeploymentPlan,
        exception: RuntimeException
    ): DeployOutcome {
        return when (
            deploymentLifecycleService.fail(
                deploymentId = plan.deploymentId,
                errorMessage = exception.message
            )
        ) {
            ManagedServiceDeploymentFinalizationResult.APPLIED -> DeployOutcome.Failed(
                exception
            )

            ManagedServiceDeploymentFinalizationResult.CANCELLATION_REQUESTED ->
                DeployOutcome.CancellationRequested
        }
    }

    private fun handleDeploymentFailure(
        plan: ManagedServiceDeploymentPlan,
        exception: RuntimeException
    ): Boolean {
        return when (
            deploymentLifecycleService.fail(
                deploymentId = plan.deploymentId,
                errorMessage = exception.message
            )
        ) {
            ManagedServiceDeploymentFinalizationResult.APPLIED -> false
            ManagedServiceDeploymentFinalizationResult.CANCELLATION_REQUESTED -> {
                deploymentCleanupService.cleanup(
                    projectId = plan.projectId,
                    serviceId = plan.serviceId,
                    deploymentId = plan.deploymentId
                )
                true
            }
        }
    }

    private fun createCommand(
        plan: ManagedServiceDeploymentPlan
    ): DockerContainerCreateCommand {
        val networkName = deploymentOperationGate.withLock(plan.deploymentId) {
            throwIfDeploymentCanceled(plan)
            ensureNetworkAndGetName(plan)
        }

        val bindings = deploymentOperationGate.withLock(plan.deploymentId) {
            throwIfDeploymentCanceled(plan)
            ensureAndMapVolumes(plan)
        }

        val imageRef = plan.snapshot.imageRef.toDockerImageRef()
        throwIfDeploymentCanceled(plan)
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
        return projectNetworkService.findNetwork(plan.projectId)
            ?: projectNetworkService.createNetwork(plan.projectId)
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
            projectId = plan.projectId,
            serviceId = plan.serviceId,
            serviceName = plan.serviceName,
            volumeName = volumeName
        ) ?: projectVolumeService.createVolume(
            projectId = plan.projectId,
            serviceId = plan.serviceId,
            deploymentId = plan.deploymentId,
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
        containerName(plan.projectId, plan.serviceName)

    private sealed interface DeployOutcome {
        data object CancellationRequested : DeployOutcome
        data class Failed(
            val exception: RuntimeException
        ) : DeployOutcome

        data object Finished : DeployOutcome
    }
}
