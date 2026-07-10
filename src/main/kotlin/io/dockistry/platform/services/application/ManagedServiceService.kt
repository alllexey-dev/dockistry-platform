package io.dockistry.platform.services.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.services.application.dto.ManagedServiceCreateCommand
import io.dockistry.platform.services.application.dto.ManagedServiceUpdateCommand
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceAlreadyExistsException
import io.dockistry.platform.services.domain.ManagedServiceNotFoundException
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class ManagedServiceService(
    private val clock: Clock,
    private val projectAccessGuard: ProjectAccessGuard,
    private val commandValidator: ManagedServiceCommandValidator,
    private val updateExecutor: ManagedServiceUpdateExecutor,
    private val managedServiceRepository: ManagedServiceRepository
) {
    @Transactional(readOnly = true)
    fun list(projectId: UUID, principal: Principal): List<ManagedService> {
        projectAccessGuard.requireReadable(projectId, principal)
        return managedServiceRepository.findAllByProjectId(projectId)
    }

    @Transactional(readOnly = true)
    fun get(projectId: UUID, serviceId: UUID, principal: Principal): ManagedService {
        projectAccessGuard.requireReadable(projectId, principal)
        return getExisting(projectId, serviceId)
    }

    @Transactional
    fun create(command: ManagedServiceCreateCommand): ManagedService {
        val project = projectAccessGuard.requireServicesManageable(
            command.projectId,
            command.principal
        )
        val configuration = commandValidator.validate(command)

        if (managedServiceRepository.existsByProjectIdAndName(project.id, command.name)) {
            throw alreadyExists(project.id, command.name)
        }

        val now = clock.instant()
        val service = ManagedService(
            project = project,
            name = command.name,
            configuration = configuration,
            createdAt = now,
            updatedAt = now
        )

        return saveNew(project, service)
    }

    fun update(command: ManagedServiceUpdateCommand): ManagedService {
        return try {
            updateExecutor.execute(command)
        } catch (_: ObjectOptimisticLockingFailureException) {
            updateExecutor.resolveConflict(command)
        }
    }

    @Transactional
    fun delete(projectId: UUID, serviceId: UUID, principal: Principal) {
        val project = projectAccessGuard.requireServicesManageable(projectId, principal)
        val deletedRows = managedServiceRepository.deleteByProjectIdAndId(
            project.id,
            serviceId
        )

        if (deletedRows == 0) {
            throw notFound(project.id, serviceId)
        }
    }

    private fun getExisting(projectId: UUID, serviceId: UUID): ManagedService {
        return managedServiceRepository.findByProjectIdAndId(projectId, serviceId)
            ?: throw notFound(projectId, serviceId)
    }

    private fun saveNew(project: Project, service: ManagedService): ManagedService {
        return try {
            managedServiceRepository.saveAndFlush(service)
        } catch (exception: DataIntegrityViolationException) {
            if (managedServiceRepository.existsByProjectIdAndName(project.id, service.name)) {
                throw alreadyExists(project.id, service.name)
            }

            throw exception
        }
    }

    private fun alreadyExists(
        projectId: UUID,
        serviceName: String
    ): ManagedServiceAlreadyExistsException {
        return ManagedServiceAlreadyExistsException(
            "Managed service '$serviceName' already exists in project $projectId"
        )
    }

    private fun notFound(projectId: UUID, serviceId: UUID): ManagedServiceNotFoundException {
        return ManagedServiceNotFoundException(
            "Managed service $serviceId couldn't be found in project $projectId"
        )
    }
}
