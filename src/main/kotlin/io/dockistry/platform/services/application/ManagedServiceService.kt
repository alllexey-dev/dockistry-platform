package io.dockistry.platform.services.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.projects.application.ProjectExistenceService
import io.dockistry.platform.projects.domain.ProjectNotFoundException
import io.dockistry.platform.services.application.dto.ManagedServiceCreateCommand
import io.dockistry.platform.services.application.dto.ManagedServiceUpdateCommand
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceAlreadyExistsException
import io.dockistry.platform.services.domain.ManagedServiceLifecycleStatus
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ManagedServiceService(
    private val projectAccessGuard: ProjectAccessGuard,
    private val createExecutor: ManagedServiceCreateExecutor,
    private val updateExecutor: ManagedServiceUpdateExecutor,
    private val managedServiceRepository: ManagedServiceRepository,
    private val managedServiceLifecycleGuard: ManagedServiceLifecycleGuard,
    private val managedServiceDeletionLifecycleService: ManagedServiceDeletionLifecycleService,
    private val managedServiceDeletionExecutor: ManagedServiceDeletionExecutor,
    private val projectExistenceService: ProjectExistenceService,
    private val managedServiceAccessGuard: ManagedServiceAccessGuard,
    private val managedServiceDeployExecutor: ManagedServiceDeployExecutor,
    private val managedServiceRuntimeExecutor: ManagedServiceRuntimeExecutor
) {
    @Transactional(readOnly = true)
    fun list(projectId: UUID, principal: Principal): List<ManagedService> {
        projectAccessGuard.requireReadable(projectId, principal)
        return managedServiceRepository.findAllByProjectIdAndLifecycleStatusIn(
            projectId = projectId,
            lifecycleStatuses = listOf(
                ManagedServiceLifecycleStatus.ACTIVE,
                ManagedServiceLifecycleStatus.STARTING,
                ManagedServiceLifecycleStatus.STOPPING
            )
        )
    }

    @Transactional(readOnly = true)
    fun get(projectId: UUID, serviceId: UUID, principal: Principal): ManagedService {
        projectAccessGuard.requireReadable(projectId, principal)
        return managedServiceLifecycleGuard.requireAvailableForRead(projectId, serviceId)
    }

    fun create(command: ManagedServiceCreateCommand): ManagedService {
        return try {
            createExecutor.execute(command)
        } catch (exception: DataIntegrityViolationException) {
            if (createExecutor.nameExists(command.projectId, command.name)) {
                throw ManagedServiceAlreadyExistsException(command.projectId, command.name)
            }

            if (!projectExistenceService.exists(command.projectId)) {
                throw ProjectNotFoundException("Project with provided ID couldn't be found")
            }

            throw exception
        }
    }

    fun update(command: ManagedServiceUpdateCommand): ManagedService {
        return try {
            updateExecutor.execute(command)
        } catch (_: ObjectOptimisticLockingFailureException) {
            updateExecutor.resolveConflict(command)
        }
    }

    fun deploy(
        projectId: UUID,
        serviceId: UUID,
        principal: Principal
    ): String {
        val service = managedServiceAccessGuard.requireManageable(
            projectId,
            serviceId,
            principal
        )
        return managedServiceDeployExecutor.deploy(service.id)
    }

    fun start(
        projectId: UUID,
        serviceId: UUID,
        principal: Principal
    ) {
        val service = managedServiceAccessGuard.requireManageable(
            projectId,
            serviceId,
            principal
        )
        managedServiceRuntimeExecutor.start(service.id)
    }

    fun stop(
        projectId: UUID,
        serviceId: UUID,
        principal: Principal
    ) {
        val service = managedServiceAccessGuard.requireManageable(
            projectId,
            serviceId,
            principal
        )
        managedServiceRuntimeExecutor.stop(service.id)
    }

    fun delete(
        projectId: UUID,
        serviceId: UUID,
        principal: Principal,
        removeVolumes: Boolean
    ) {
        managedServiceDeletionLifecycleService.begin(
            projectId = projectId,
            serviceId = serviceId,
            principal = principal,
            removeVolumes = removeVolumes
        )

        managedServiceDeletionExecutor.execute(projectId, serviceId)
    }
}
