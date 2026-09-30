package io.dockistry.platform.services.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.secrets.application.ManagedServiceSecretReferenceService
import io.dockistry.platform.services.application.dto.ManagedServiceDetails
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceSecretReference
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ManagedServiceQueryService(
    private val managedServiceService: ManagedServiceService,
    private val secretReferenceService: ManagedServiceSecretReferenceService
) {
    @Transactional(readOnly = true)
    fun list(projectId: UUID, principal: Principal): List<ManagedServiceDetails> {
        val services = managedServiceService.list(projectId, principal)
        val serviceIds = services.map { it.id }
        val referencesByService = secretReferenceService.getReferences(serviceIds)

        return services.map { service ->
            ManagedServiceDetails(
                service = service,
                secretReferences = referencesByService[service.id].orEmpty()
            )
        }
    }

    @Transactional(readOnly = true)
    fun get(
        projectId: UUID,
        serviceId: UUID,
        principal: Principal
    ): ManagedServiceDetails {
        val service = managedServiceService.get(projectId, serviceId, principal)
        return ManagedServiceDetails(
            service = service,
            secretReferences = secretReferenceService.getReferences(service.id)
        )
    }

    fun withKnownReferences(
        service: ManagedService,
        secretReferences: List<ManagedServiceSecretReference>
    ): ManagedServiceDetails {
        return ManagedServiceDetails(
            service = service,
            secretReferences = secretReferences
        )
    }
}
