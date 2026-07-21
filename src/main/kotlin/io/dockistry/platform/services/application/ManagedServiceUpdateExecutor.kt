package io.dockistry.platform.services.application

import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.services.application.dto.ManagedServiceUpdateCommand
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceVersionConflictException
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class ManagedServiceUpdateExecutor(
    private val clock: Clock,
    private val projectAccessGuard: ProjectAccessGuard,
    private val commandValidator: ManagedServiceCommandValidator,
    private val managedServiceLifecycleGuard: ManagedServiceLifecycleGuard,
    private val managedServiceRepository: ManagedServiceRepository
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
        val configuration = commandValidator.validate(command)

        if (service.version != command.expectedVersion) {
            if (service.matches(configuration)) {
                return service
            }

            throw versionConflict(service)
        }

        if (service.matches(configuration)) {
            return service
        }

        service.updateConfiguration(configuration, clock.instant())
        return managedServiceRepository.saveAndFlush(service)
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
        val configuration = commandValidator.validate(command)

        if (service.matches(configuration)) {
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
