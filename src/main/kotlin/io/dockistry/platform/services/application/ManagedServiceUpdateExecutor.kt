package io.dockistry.platform.services.application

import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.secrets.application.ManagedServiceSecretReferenceService
import io.dockistry.platform.secrets.domain.toDto
import io.dockistry.platform.secrets.infrastructure.ManagedServiceSecretReferenceRepository
import io.dockistry.platform.services.application.dto.ManagedServiceUpdateCommand
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceVersionConflictException
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class ManagedServiceUpdateExecutor(
    private val clock: Clock,
    private val projectAccessGuard: ProjectAccessGuard,
    private val commandValidator: ManagedServiceCommandValidator,
    private val managedServiceLifecycleGuard: ManagedServiceLifecycleGuard,
    private val managedServiceSecretReferenceService: ManagedServiceSecretReferenceService,
    private val managedServiceRepository: ManagedServiceRepository,
    private val referenceRepository: ManagedServiceSecretReferenceRepository,
    private val managedServiceSecretUsageGuard: ManagedServiceSecretUsageGuard,
    private val entityManager: EntityManager
) {
    @Transactional
    fun execute(command: ManagedServiceUpdateCommand): ManagedService {
        val project = projectAccessGuard.requireServicesManageable(
            command.projectId,
            command.principal
        )
        val service = managedServiceLifecycleGuard.requireActiveForMutationWithWait(
            projectId = project.id,
            serviceId = command.serviceId
        )

        val currentReferences =
            managedServiceSecretReferenceService.getReferences(service.id)

        managedServiceSecretUsageGuard.requireSecretsUsable(
            project = project,
            principal = command.principal,
            resultingReferences = command.configuration.secretReferences,
            currentReferences = currentReferences
        )

        val configuration = commandValidator.validate(command)

        val existingReferences =
            referenceRepository.findByServiceId(service.id).map { it.toDto() }

        if (service.version != command.expectedVersion) {
            if (service.matches(configuration, existingReferences)) {
                return service
            }

            throw versionConflict(service)
        }

        if (service.matches(configuration, existingReferences)) {
            return service
        }

        val referencesOnlyChange = service.matches(
            configuration,
            configuration.secretReferences
        )
        managedServiceSecretReferenceService.replace(service, configuration.secretReferences)
        service.updateConfiguration(configuration, clock.instant())
        val updated = managedServiceRepository.saveAndFlush(service)
        if (referencesOnlyChange) {
            entityManager.lock(updated, LockModeType.OPTIMISTIC_FORCE_INCREMENT)
            entityManager.flush()
        }
        return updated
    }

    @Transactional
    fun resolveConflict(command: ManagedServiceUpdateCommand): ManagedService {
        val project = projectAccessGuard.requireServicesManageable(
            command.projectId,
            command.principal
        )
        val service = managedServiceLifecycleGuard.requireActiveForMutationWithWait(
            projectId = project.id,
            serviceId = command.serviceId
        )
        val currentReferences = managedServiceSecretReferenceService.getReferences(service.id)
        managedServiceSecretUsageGuard.requireSecretsUsable(
            project = project,
            principal = command.principal,
            resultingReferences = command.configuration.secretReferences,
            currentReferences = currentReferences
        )
        val configuration = commandValidator.validate(command)

        if (service.matches(configuration, currentReferences)) {
            return service
        }

        throw versionConflict(service)
    }

    private fun versionConflict(service: ManagedService): ManagedServiceVersionConflictException {
        return ManagedServiceVersionConflictException(
            "Managed service ${service.id} has been modified concurrently"
        )
    }
}
