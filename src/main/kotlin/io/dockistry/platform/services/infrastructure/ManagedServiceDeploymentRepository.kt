package io.dockistry.platform.services.infrastructure

import io.dockistry.platform.services.domain.ManagedServiceDeployment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface ManagedServiceDeploymentRepository :
    JpaRepository<ManagedServiceDeployment, UUID> {

    @Query("""
        SELECT deployment
        FROM ManagedServiceDeployment deployment
        WHERE deployment.service.id = :serviceId
          AND deployment.status IN (
              ManagedServiceDeploymentStatus.IN_PROCESS,
              ManagedServiceDeploymentStatus.CANCELING
          )
    """)
    fun findActiveByServiceId(
        @Param("serviceId") serviceId: UUID
    ): List<ManagedServiceDeployment>

    @Query("""
        SELECT EXISTS (
            SELECT 1
            FROM ManagedServiceDeployment deployment
            WHERE deployment.service.id = :serviceId
              AND deployment.status IN (
                  ManagedServiceDeploymentStatus.IN_PROCESS,
                  ManagedServiceDeploymentStatus.CANCELING
              )
        )
    """)
    fun hasActiveDeployments(@Param("serviceId") serviceId: UUID): Boolean

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ManagedServiceDeployment deployment
        SET deployment.status = ManagedServiceDeploymentStatus.CANCELING,
            deployment.updatedAt = :updatedAt
        WHERE deployment.id = :deploymentId
          AND deployment.status = ManagedServiceDeploymentStatus.IN_PROCESS
    """)
    fun markCancelingIfInProcess(
        @Param("deploymentId") deploymentId: UUID,
        @Param("updatedAt") updatedAt: Instant
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ManagedServiceDeployment deployment
        SET deployment.status = ManagedServiceDeploymentStatus.FINISHED,
            deployment.containerId = :containerId,
            deployment.updatedAt = :updatedAt
        WHERE deployment.id = :deploymentId
          AND deployment.status = ManagedServiceDeploymentStatus.IN_PROCESS
    """)
    fun markFinishedIfInProcess(
        @Param("deploymentId") deploymentId: UUID,
        @Param("containerId") containerId: String,
        @Param("updatedAt") updatedAt: Instant
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ManagedServiceDeployment deployment
        SET deployment.status = ManagedServiceDeploymentStatus.FAILED,
            deployment.errorMessage = :errorMessage,
            deployment.updatedAt = :updatedAt
        WHERE deployment.id = :deploymentId
          AND deployment.status = ManagedServiceDeploymentStatus.IN_PROCESS
    """)
    fun markFailedIfInProcess(
        @Param("deploymentId") deploymentId: UUID,
        @Param("errorMessage") errorMessage: String?,
        @Param("updatedAt") updatedAt: Instant
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ManagedServiceDeployment deployment
        SET deployment.status = ManagedServiceDeploymentStatus.CANCELED,
            deployment.updatedAt = :updatedAt
        WHERE deployment.id = :deploymentId
          AND deployment.status = ManagedServiceDeploymentStatus.CANCELING
    """)
    fun markCanceledIfCanceling(
        @Param("deploymentId") deploymentId: UUID,
        @Param("updatedAt") updatedAt: Instant
    ): Int
}
