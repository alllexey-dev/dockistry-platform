package io.dockistry.platform.services.application

import io.dockistry.platform.services.application.dto.ManagedServiceDeploymentPlan
import io.dockistry.platform.services.domain.DeployAlreadyInProcessException
import io.dockistry.platform.services.domain.DeploymentNotFoundException
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceConfiguration
import io.dockistry.platform.services.domain.ManagedServiceDeployment
import io.dockistry.platform.services.domain.ManagedServiceDeploymentStatus
import io.dockistry.platform.services.domain.ManagedServiceDesiredState
import io.dockistry.platform.services.infrastructure.ManagedServiceDeploymentRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class ManagedServiceDeploymentLifecycleService(
    private val managedServiceLifecycleGuard: ManagedServiceLifecycleGuard,
    private val deploymentRepository: ManagedServiceDeploymentRepository,
    private val clock: Clock
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun begin(serviceId: UUID): ManagedServiceDeploymentPlan {
        val service = managedServiceLifecycleGuard
            .requireActiveForMutationWithWait(serviceId)

        val snapshot = ManagedServiceConfiguration.validated(
            service.toConfigurationDraft()
        )
        val deployment = saveDeployment(service, snapshot)

        service.updateDesiredState(ManagedServiceDesiredState.RUNNING, clock.instant())

        return ManagedServiceDeploymentPlan(
            deploymentId = deployment.id,
            serviceId = service.id,
            projectId = service.project.id,
            serviceName = service.name,
            snapshot = snapshot
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun finish(
        deploymentId: UUID,
        containerId: String
    ): ManagedServiceDeploymentFinalizationResult {
        val updatedRows = deploymentRepository.markFinishedIfInProcess(
            deploymentId = deploymentId,
            containerId = containerId,
            updatedAt = clock.instant()
        )

        return resolveFinalization(
            deploymentId = deploymentId,
            operation = "finish",
            updatedRows = updatedRows,
            containerId = containerId
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun fail(
        deploymentId: UUID,
        errorMessage: String?
    ): ManagedServiceDeploymentFinalizationResult {
        val updatedRows = deploymentRepository.markFailedIfInProcess(
            deploymentId = deploymentId,
            errorMessage = errorMessage,
            updatedAt = clock.instant()
        )

        return resolveFinalization(
            deploymentId = deploymentId,
            operation = "fail",
            updatedRows = updatedRows,
            containerId = null
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeCancellation(deploymentId: UUID) {
        when (
            val updatedRows = deploymentRepository.markCanceledIfCanceling(
                deploymentId = deploymentId,
                updatedAt = clock.instant()
            )
        ) {
            1 -> Unit
            0 -> {
                val status = getDeployment(deploymentId).status
                if (status != ManagedServiceDeploymentStatus.CANCELED) {
                    throw unexpectedState(
                        deploymentId = deploymentId,
                        operation = "complete cancellation",
                        status = status
                    )
                }
            }
            else -> throw unexpectedUpdatedRows(
                deploymentId = deploymentId,
                operation = "complete cancellation",
                updatedRows = updatedRows
            )
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failCleanup(deploymentId: UUID, errorMessage: String?) {
        when (
            val updatedRows = deploymentRepository.markCleanupFailedIfCanceling(
                deploymentId = deploymentId,
                errorMessage = errorMessage,
                attemptedAt = clock.instant()
            )
        ) {
            1 -> Unit
            0 -> {
                val status = getDeployment(deploymentId).status
                if (status != ManagedServiceDeploymentStatus.CANCELED) {
                    throw unexpectedState(
                        deploymentId = deploymentId,
                        operation = "record cleanup failure",
                        status = status
                    )
                }
            }
            else -> throw unexpectedUpdatedRows(
                deploymentId = deploymentId,
                operation = "record cleanup failure",
                updatedRows = updatedRows
            )
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun requestDeploymentCancellation(
        deploymentId: UUID
    ): ManagedServiceDeploymentCancellationResult {
        val deployment = deploymentRepository.findById(deploymentId).orElseThrow {
            DeploymentNotFoundException("Deployment $deploymentId couldn't be found")
        }

        if (deployment.status == ManagedServiceDeploymentStatus.CANCELING
            || deployment.status == ManagedServiceDeploymentStatus.CANCELED
        ) {
            return ManagedServiceDeploymentCancellationResult.Requested
        }
        if (deployment.status != ManagedServiceDeploymentStatus.IN_PROCESS) {
            throw unexpectedState(
                deploymentId = deployment.id,
                operation = "request cancellation",
                status = deployment.status
            )
        }

        return when (
            val updatedRows = deploymentRepository.markCancelingIfInProcess(
                deploymentId = deployment.id,
                updatedAt = clock.instant()
            )
        ) {
            1 -> ManagedServiceDeploymentCancellationResult.Requested
            0 -> resolveCancellationRace(deployment.id)
            else -> throw unexpectedUpdatedRows(
                deploymentId = deployment.id,
                operation = "request cancellation",
                updatedRows = updatedRows
            )
        }
    }

    @Transactional(
        readOnly = true,
        propagation = Propagation.REQUIRES_NEW
    )
    fun isCancellationRequested(deploymentId: UUID): Boolean {
        return when (getDeployment(deploymentId).status) {
            ManagedServiceDeploymentStatus.CANCELING,
            ManagedServiceDeploymentStatus.CANCELED -> true

            else -> false
        }
    }

    @Transactional(
        readOnly = true,
        propagation = Propagation.REQUIRES_NEW
    )
    fun hasActiveDeployment(serviceId: UUID): Boolean {
        return deploymentRepository.hasActiveDeployments(serviceId)
    }

    private fun resolveFinalization(
        deploymentId: UUID,
        operation: String,
        updatedRows: Int,
        containerId: String?
    ): ManagedServiceDeploymentFinalizationResult {
        return when (updatedRows) {
            1 -> ManagedServiceDeploymentFinalizationResult.APPLIED
            0 -> {
                val deployment = getDeployment(deploymentId)

                when (deployment.status) {
                    /* If reaper concurrently noticed created container and marked deployment
                   as finished, we don't clean up or throw exception, we consider deployment
                   gracefully finished.
                    */
                    ManagedServiceDeploymentStatus.FINISHED -> {
                        if (containerId == null) {
                            throw unexpectedState(deploymentId, operation, deployment.status)
                        } else if (deployment.containerId == null || deployment.containerId != containerId) {
                            throw InternalDataIntegrityException("Deployment finished with a container ID ${deployment.containerId}, but expected to mark finished with $containerId container ID")
                        }
                        ManagedServiceDeploymentFinalizationResult.APPLIED
                    }

                    ManagedServiceDeploymentStatus.CANCELING,
                    ManagedServiceDeploymentStatus.CANCELED ->
                        ManagedServiceDeploymentFinalizationResult.CANCELLATION_REQUESTED

                    else -> throw unexpectedState(deploymentId, operation, deployment.status)
                }
            }
            else -> throw unexpectedUpdatedRows(deploymentId, operation, updatedRows)
        }
    }

    private fun resolveCancellationRace(
        deploymentId: UUID
    ): ManagedServiceDeploymentCancellationResult {
        val deployment = getDeployment(deploymentId)
        return when (deployment.status) {
            ManagedServiceDeploymentStatus.CANCELING,
            ManagedServiceDeploymentStatus.CANCELED ->
                ManagedServiceDeploymentCancellationResult.Requested

            ManagedServiceDeploymentStatus.FINISHED -> {
                val containerId = deployment.containerId
                    ?: throw InternalDataIntegrityException(
                        "Finished managed service deployment $deploymentId has no container ID"
                    )
                ManagedServiceDeploymentCancellationResult.DeploymentFinished(containerId)
            }
            ManagedServiceDeploymentStatus.FAILED ->
                ManagedServiceDeploymentCancellationResult.NoDeploymentInProcess

            ManagedServiceDeploymentStatus.IN_PROCESS -> throw unexpectedState(
                deploymentId = deploymentId,
                operation = "request cancellation",
                status = deployment.status
            )
        }
    }

    private fun getDeployment(deploymentId: UUID): ManagedServiceDeployment {
        return deploymentRepository.findById(deploymentId)
            .orElseThrow {
                InternalDataIntegrityException(
                    "Managed service deployment $deploymentId was created, " +
                            "but couldn't be found in DB"
                )
            }
    }

    private fun unexpectedState(
        deploymentId: UUID,
        operation: String,
        status: ManagedServiceDeploymentStatus
    ): InternalDataIntegrityException {
        return InternalDataIntegrityException(
            "Managed service deployment $deploymentId couldn't $operation because its " +
                    "status is $status"
        )
    }

    private fun unexpectedUpdatedRows(
        deploymentId: UUID,
        operation: String,
        updatedRows: Int
    ): InternalDataIntegrityException {
        return InternalDataIntegrityException(
            "Managed service deployment $deploymentId $operation CAS updated " +
                    "$updatedRows rows instead of one"
        )
    }

    private fun saveDeployment(
        service: ManagedService,
        snapshot: ManagedServiceConfiguration
    ): ManagedServiceDeployment {
        return ManagedServiceDeployment(
            service = service,
            configSnapshot = snapshot,
            serviceVersion = service.version,
            createdAt = clock.instant(),
            updatedAt = clock.instant(),
        ).let {
            try {
                deploymentRepository.saveAndFlush(it)
            } catch (_: DataIntegrityViolationException) {
                throw DeployAlreadyInProcessException(
                    "Deploy for this service is already in process"
                )
            }
        }
    }
}
