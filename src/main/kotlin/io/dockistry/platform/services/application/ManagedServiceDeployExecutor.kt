package io.dockistry.platform.services.application

import io.dockistry.platform.docker.application.ContainerService
import io.dockistry.platform.docker.domain.DockerContainerCreateNameConflictException
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.model.DockerContainerCreateCommand
import io.dockistry.platform.docker.domain.model.DockerContainerState
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerImageRef
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.docker.domain.model.DockerPortBinding
import io.dockistry.platform.docker.domain.model.DockerPortProtocol
import io.dockistry.platform.docker.domain.model.DockerRestartPolicy
import io.dockistry.platform.docker.domain.model.DockerVolumeBinding
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.secrets.application.ports.SecretEncryptor
import io.dockistry.platform.services.application.dto.ManagedServiceDeploymentPlan
import io.dockistry.platform.services.domain.ContainerNameConflictSupportRequiredException
import io.dockistry.platform.services.domain.DeployAlreadyInProcessException
import io.dockistry.platform.services.domain.ManagedServiceDeploymentCanceled
import io.dockistry.platform.services.domain.ManagedServiceImageRef
import io.dockistry.platform.services.domain.ManagedServiceStopRequiredException
import io.dockistry.platform.services.domain.ServiceEndpointMode
import io.dockistry.platform.services.domain.ServiceEndpointProtocol
import io.dockistry.platform.services.infrastructure.ManagedServiceDeploymentRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ManagedServiceDeployExecutor(
    private val deploymentLifecycleService: ManagedServiceDeploymentLifecycleService,
    private val deploymentCleanupService: ManagedServiceDeploymentCleanupService,
    private val projectNetworkService: ProjectNetworkService,
    private val projectVolumeService: ProjectVolumeService,
    private val containerService: ContainerService,
    private val deploymentOperationGate: ManagedServiceDeploymentOperationGate,
    private val projectOperationGate: ProjectOperationGate,
    private val deploymentRepository: ManagedServiceDeploymentRepository,
    private val secretEncryptor: SecretEncryptor
) {

    companion object {
        private val logger: Logger =
            LoggerFactory.getLogger(ManagedServiceDeployExecutor::class.java)

        internal fun containerName(projectSlug: String, serviceName: String): String =
            "dockistry_${projectSlug}_$serviceName"
    }

    fun deploy(serviceId: UUID, principal: Principal, ignoreWarnings: Boolean): String {
        if (deploymentRepository.hasActiveDeployments(serviceId)) {
            throw DeployAlreadyInProcessException(
                "Deploy for this service is already in process"
            )
        }

        val plan = try {
            deploymentLifecycleService.begin(serviceId, principal, ignoreWarnings)
        } catch (exception: DataIntegrityViolationException) {
            if (deploymentRepository.hasActiveDeployments(serviceId)) {
                throw DeployAlreadyInProcessException(
                    "Deploy for this service is already in process"
                )
            }

            throw exception
        }

        val command = try {
            createCommand(plan)
        } catch (exception: RuntimeException) {
            if (recordDeploymentFailureOrAutoCleanupWithGate(plan, exception)) {
                throw canceledDeployment()
            }
            throw exception
        }

        val outcome = try {
            deploymentOperationGate.withLock(plan.deploymentId) {
                try {
                    val containerId = deployToDocker(plan, command)

                    when (
                        deploymentLifecycleService.finish(
                            deploymentId = plan.deploymentId,
                            containerId = containerId
                        )
                    ) {
                        ManagedServiceDeploymentFinalizationResult.APPLIED ->
                            DeployOutcome.Finished(containerId)

                        ManagedServiceDeploymentFinalizationResult.CANCELLATION_REQUESTED ->
                            DeployOutcome.CancellationRequested
                    }
                } catch (_: DockerContainerCreateNameConflictException) {
                    recordFailureWithOutcomeUnderGate(
                        plan,
                        ContainerNameConflictSupportRequiredException()
                    )
                } catch (exception: RuntimeException) {
                    recordFailureWithOutcomeUnderGate(plan, exception)
                }
            }
        } catch (exception: RuntimeException) {
            if (recordDeploymentFailureOrAutoCleanupWithGate(plan, exception)) {
                throw canceledDeployment()
            }
            throw exception
        }

        when (outcome) {
            is DeployOutcome.Finished -> return outcome.containerId

            DeployOutcome.CancellationRequested -> {
                deploymentCleanupService.cleanup(
                    projectId = plan.projectId,
                    serviceId = plan.serviceId,
                    deploymentId = plan.deploymentId
                )
                throw canceledDeployment()
            }

            is DeployOutcome.Failed -> throw outcome.exception
        }
    }

    private fun deployToDocker(
        plan: ManagedServiceDeploymentPlan,
        command: DockerContainerCreateCommand
    ): String {
        return try {
            throwIfDeploymentCanceled(plan)
            val containerId = containerService.createContainer(command)
            containerService.startContainer(containerId)

            containerId
        } catch (exception: DockerContainerCreateNameConflictException) {
            throwIfDeploymentCanceled(plan)

            if (!handleConflict(plan)) {
                throw exception
            }

            // If conflicting container was automatically deleted, try again once
            throwIfDeploymentCanceled(plan)
            val containerId = containerService.createContainer(command)
            containerService.startContainer(containerId)

            containerId
        }
    }

    private fun throwIfDeploymentCanceled(plan: ManagedServiceDeploymentPlan) {
        if (deploymentLifecycleService.isCancellationRequested(plan.deploymentId)) {
            throw ManagedServiceDeploymentCanceled("This deployment was canceled")
        }
    }

    private fun recordFailureWithOutcomeUnderGate(
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

    private fun recordDeploymentFailureOrAutoCleanupWithGate(
        plan: ManagedServiceDeploymentPlan,
        exception: RuntimeException
    ): Boolean {
        return try {
            when (
                deploymentOperationGate.withLock(plan.deploymentId) {
                    deploymentLifecycleService.fail(
                        deploymentId = plan.deploymentId,
                        errorMessage = exception.message
                    )
                }
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
        } catch (recordException: Exception) {
            recordException.addSuppressed(exception)
            throw recordException
        }
    }

    private fun createCommand(
        plan: ManagedServiceDeploymentPlan
    ): DockerContainerCreateCommand {
        // Decrypt failure must stop the deploy before it creates any Docker resource.
        val secretBackedEnv = plan.secretPayloads.associate {
            it.envName to secretEncryptor.decrypt(it.encryptedPayload, it.context)
        }

        val networkName = projectOperationGate.withLock(plan.projectId) {
            throwIfDeploymentCanceled(plan)
            ensureNetworkAndGetName(plan)
        }

        val bindings = deploymentOperationGate.withLock(plan.deploymentId) {
            throwIfDeploymentCanceled(plan)
            ensureAndMapVolumes(plan)
        }

        val imageRef = plan.snapshot.imageRef.toDockerImageRef()
        throwIfDeploymentCanceled(plan)
        containerService.pullImage(imageRef)

        return DockerContainerCreateCommand(
            name = containerName(plan),
            image = imageRef,
            env = plan.snapshot.env + secretBackedEnv,
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

    private fun handleConflict(plan: ManagedServiceDeploymentPlan): Boolean {
        val containerDetails = getConflictingContainer(plan)?.takeIf {
            it.labels[DockerLabels.DEPLOYMENT_ID] != null
        }

        if (containerDetails != null) {
            if (containerDetails.state !in listOf(
                DockerContainerState.CREATED,
                DockerContainerState.EXITED,
                DockerContainerState.DEAD
            )) {
                throw ManagedServiceStopRequiredException()
            }

            try {
                containerService.deleteContainer(containerDetails.id)
                return true
            } catch (exception: DockerContainerNotFoundException) {
                logger.error(
                    "Container ${containerDetails.id} not found while handling " +
                            "container creation name conflict, even though it was just found " +
                            "through ContainerService.listContainers",
                    exception
                )
            }
        }
        return false
    }

    private fun getConflictingContainer(
        plan: ManagedServiceDeploymentPlan
    ): DockerContainerSummary? {
        return containerService.listContainers(
            DockerLabelsFilter(
                labels = DockerLabels.forService(plan.projectId, plan.serviceId)
            )
        ).firstOrNull {
            it.name == containerName(plan)
        }
    }

    private fun ensureNetworkAndGetName(plan: ManagedServiceDeploymentPlan): String {
        return projectNetworkService.findNetwork(
            projectId = plan.projectId,
            projectSlug = plan.projectSlug
        ) ?: projectNetworkService.createNetwork(
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
            projectId = plan.projectId,
            projectSlug = plan.projectSlug,
            serviceId = plan.serviceId,
            serviceName = plan.serviceName,
            volumeName = volumeName
        ) ?: projectVolumeService.createVolume(
            projectId = plan.projectId,
            projectSlug = plan.projectSlug,
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

    private fun canceledDeployment() =
        ManagedServiceDeploymentCanceled("This deployment was canceled")

    private fun containerName(plan: ManagedServiceDeploymentPlan): String =
        containerName(plan.projectSlug, plan.serviceName)

    private sealed interface DeployOutcome {
        data object CancellationRequested : DeployOutcome
        data class Failed(
            val exception: RuntimeException
        ) : DeployOutcome

        data class Finished(
            val containerId: String
        ) : DeployOutcome
    }
}
