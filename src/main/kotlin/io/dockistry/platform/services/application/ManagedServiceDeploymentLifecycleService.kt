package io.dockistry.platform.services.application

import io.dockistry.platform.services.application.dto.ManagedServiceDeploymentPlan
import io.dockistry.platform.services.domain.DeployAlreadyInProcessException
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceConfiguration
import io.dockistry.platform.services.domain.ManagedServiceDeployment
import io.dockistry.platform.services.domain.ManagedServiceDesiredState
import io.dockistry.platform.services.domain.ManagedServiceNotFoundException
import io.dockistry.platform.services.infrastructure.ManagedServiceDeploymentRepository
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class ManagedServiceDeploymentLifecycleService(
    private val managedServiceRepository: ManagedServiceRepository,
    private val deploymentRepository: ManagedServiceDeploymentRepository,
    private val clock: Clock
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun begin(serviceId: UUID): ManagedServiceDeploymentPlan {
        val service = managedServiceRepository.findById(serviceId)
            .orElseThrow {
                ManagedServiceNotFoundException("Managed service couldn't be found")
            }

        val snapshot = ManagedServiceConfiguration.validated(
            service.toConfigurationDraft()
        )
        val deployment = saveDeployment(service, snapshot)

        service.updateDesiredState(ManagedServiceDesiredState.RUNNING)

        return ManagedServiceDeploymentPlan(
            deploymentId = deployment.id,
            serviceId = service.id,
            projectId = service.project.id,
            projectSlug = service.project.slug,
            serviceName = service.name,
            snapshot = snapshot
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun finish(deploymentId: UUID, containerId: String) {
        getDeployment(deploymentId).finish(
            containerId = containerId,
            updatedAt = clock.instant()
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun fail(deploymentId: UUID, errorMessage: String?) {
        getDeployment(deploymentId).fail(
            errorMessage = errorMessage,
            updatedAt = clock.instant()
        )
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
