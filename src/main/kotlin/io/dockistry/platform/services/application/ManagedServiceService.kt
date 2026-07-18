package io.dockistry.platform.services.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.services.application.dto.ManagedServiceCreateCommand
import io.dockistry.platform.services.application.dto.ManagedServiceUpdateCommand
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceAlreadyExistsException
import io.dockistry.platform.services.domain.ManagedServiceDeletionBlockedByDeployment
import io.dockistry.platform.services.domain.ManagedServiceNotFoundException
import io.dockistry.platform.services.infrastructure.ManagedServiceDeploymentRepository
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Service
class ManagedServiceService(
    transactionManager: PlatformTransactionManager,

    private val projectAccessGuard: ProjectAccessGuard,
    private val createExecutor: ManagedServiceCreateExecutor,
    private val updateExecutor: ManagedServiceUpdateExecutor,
    private val managedServiceRepository: ManagedServiceRepository,
    private val projectVolumeService: ProjectVolumeService,
    private val managedServiceRuntimeExecutor: ManagedServiceRuntimeExecutor,
    private val deploymentRepository: ManagedServiceDeploymentRepository
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

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

    fun create(command: ManagedServiceCreateCommand): ManagedService {
        return try {
            createExecutor.execute(command)
        } catch (exception: DataIntegrityViolationException) {
            if (createExecutor.nameExists(command.projectId, command.name)) {
                throw ManagedServiceAlreadyExistsException(command.projectId, command.name)
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

    fun delete(projectId: UUID, serviceId: UUID, principal: Principal,
               removeVolumes: Boolean) {
        transactionTemplate.execute {
            projectAccessGuard.requireServicesManageable(projectId, principal)

            if (deploymentRepository.hasActiveDeployments(serviceId)) {
                throw ManagedServiceDeletionBlockedByDeployment("This service is currently being deployed")
            }
        }

        managedServiceRuntimeExecutor.removeContainer(
            projectId = projectId,
            serviceId = serviceId
        )

        if (removeVolumes) {
            projectVolumeService.removeServiceVolumesByLabels(
                projectId = projectId,
                serviceId = serviceId
            )
        }

        val deletedRows = transactionTemplate.execute {
            managedServiceRepository.deleteByProjectIdAndId(
                projectId = projectId,
                serviceId = serviceId
            )
        }

        if (deletedRows == 0) {
            throw notFound(projectId = projectId, serviceId = serviceId)
        }
    }

    private fun getExisting(projectId: UUID, serviceId: UUID): ManagedService {
        return managedServiceRepository.findByProjectIdAndId(projectId, serviceId)
            ?: throw notFound(projectId, serviceId)
    }

    private fun notFound(projectId: UUID, serviceId: UUID): ManagedServiceNotFoundException {
        return ManagedServiceNotFoundException(
            "Managed service $serviceId couldn't be found in project $projectId"
        )
    }
}
