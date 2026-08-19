package io.dockistry.platform.services.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceNotFoundException
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ManagedServiceAccessGuard(
    private val projectAccessGuard: ProjectAccessGuard,
    private val managedServiceRepository: ManagedServiceRepository,
    private val managedServiceLifecycleGuard: ManagedServiceLifecycleGuard
) {
    @Transactional(readOnly = true)
    fun requireReadable(
        projectId: UUID,
        serviceId: UUID,
        principal: Principal
    ): ManagedService {
        projectAccessGuard.requireReadable(projectId, principal)
        return managedServiceLifecycleGuard.requireAvailableForRead(projectId, serviceId)
    }

    @Transactional(readOnly = true)
    fun requireManageable(
        projectId: UUID,
        serviceId: UUID,
        principal: Principal
    ): ManagedService {
        projectAccessGuard.requireServicesManageable(projectId, principal)

        return managedServiceRepository.findByProjectIdAndId(projectId, serviceId)
            ?: throw ManagedServiceNotFoundException(
                "Managed service $serviceId couldn't be found in project $projectId"
            )
    }
}
