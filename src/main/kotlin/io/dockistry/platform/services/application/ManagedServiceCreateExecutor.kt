package io.dockistry.platform.services.application

import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.services.application.dto.ManagedServiceCreateCommand
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceAlreadyExistsException
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class ManagedServiceCreateExecutor(
    private val clock: Clock,
    private val projectAccessGuard: ProjectAccessGuard,
    private val commandValidator: ManagedServiceCommandValidator,
    private val managedServiceRepository: ManagedServiceRepository
) {
    @Transactional
    fun execute(command: ManagedServiceCreateCommand): ManagedService {
        val project = projectAccessGuard.requireServicesManageable(
            command.projectId,
            command.principal
        )
        val configuration = commandValidator.validate(command)

        if (managedServiceRepository.existsByProjectIdAndName(project.id, command.name)) {
            throw ManagedServiceAlreadyExistsException(project.id, command.name)
        }

        val now = clock.instant()
        val service = ManagedService(
            project = project,
            name = command.name,
            configuration = configuration,
            createdAt = now,
            updatedAt = now
        )

        return managedServiceRepository.saveAndFlush(service)
    }

    @Transactional(readOnly = true)
    fun nameExists(projectId: UUID, serviceName: String): Boolean {
        return managedServiceRepository.existsByProjectIdAndName(projectId, serviceName)
    }
}
