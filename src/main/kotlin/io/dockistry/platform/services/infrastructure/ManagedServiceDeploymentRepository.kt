package io.dockistry.platform.services.infrastructure

import io.dockistry.platform.services.domain.ManagedServiceDeployment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ManagedServiceDeploymentRepository :
    JpaRepository<ManagedServiceDeployment, UUID> {
    fun findByServiceId(serviceId: UUID): MutableList<ManagedServiceDeployment>

    @Query("""
        SELECT EXISTS (
            SELECT 1
            FROM ManagedServiceDeployment deployment
            WHERE deployment.status = ManagedServiceDeploymentStatus.IN_PROCESS
        )
    """)
    fun hasActiveDeployments(@Param("serviceId") serviceId: UUID): Boolean
}
