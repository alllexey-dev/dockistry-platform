package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.secrets.domain.ServiceSecretWarning
import io.dockistry.platform.services.domain.ManagedService
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ServiceSecretWarningRepository : JpaRepository<ServiceSecretWarning, UUID> {
    fun findByServiceAndIsActiveTrueOrderByCreatedAtAsc(
        service: ManagedService
    ): List<ServiceSecretWarning>
}
