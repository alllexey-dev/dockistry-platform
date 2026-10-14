package io.dockistry.platform.services.infrastructure

import io.dockistry.platform.services.domain.ManagedServiceDeployment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
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
        serviceId: UUID
    ): List<ManagedServiceDeployment>

    @Query("""
        SELECT deployment.id
        FROM ManagedServiceDeployment deployment
        WHERE deployment.service.id = :serviceId
          AND deployment.status IN (
              ManagedServiceDeploymentStatus.IN_PROCESS,
              ManagedServiceDeploymentStatus.CANCELING
          )
    """)
    fun findActiveIdsByServiceId(
        serviceId: UUID
    ): List<UUID>

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
    fun hasActiveDeployments(serviceId: UUID): Boolean

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ManagedServiceDeployment deployment
        SET deployment.status = ManagedServiceDeploymentStatus.CANCELING,
            deployment.updatedAt = :updatedAt
        WHERE deployment.id = :deploymentId
          AND deployment.status = ManagedServiceDeploymentStatus.IN_PROCESS
    """)
    fun markCancelingIfInProcess(
        deploymentId: UUID,
        updatedAt: Instant
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
        deploymentId: UUID,
        containerId: String,
        updatedAt: Instant
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
        deploymentId: UUID,
        errorMessage: String?,
        updatedAt: Instant
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ManagedServiceDeployment deployment
        SET deployment.status = ManagedServiceDeploymentStatus.CANCELED,
            deployment.cleanupErrorMessage = NULL,
            deployment.lastCleanupAttemptAt = :updatedAt,
            deployment.updatedAt = :updatedAt
        WHERE deployment.id = :deploymentId
          AND deployment.status = ManagedServiceDeploymentStatus.CANCELING
    """)
    fun markCanceledIfCanceling(
        deploymentId: UUID,
        updatedAt: Instant
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ManagedServiceDeployment deployment
        SET deployment.cleanupErrorMessage = :errorMessage,
            deployment.lastCleanupAttemptAt = :attemptedAt,
            deployment.updatedAt = :attemptedAt
        WHERE deployment.id = :deploymentId
          AND deployment.status = ManagedServiceDeploymentStatus.CANCELING
    """)
    fun markCleanupFailedIfCanceling(
        deploymentId: UUID,
        errorMessage: String?,
        attemptedAt: Instant
    ): Int

    @Query("""
        SELECT deployment
        FROM ManagedServiceDeployment deployment
        JOIN FETCH deployment.service
        WHERE (deployment.status = ManagedServiceDeploymentStatus.IN_PROCESS
            OR deployment.status = ManagedServiceDeploymentStatus.CANCELING
            ) AND deployment.updatedAt < :updatedBefore
        ORDER BY deployment.updatedAt
        LIMIT :batchLimit
    """)
    fun findStuckDeploymentsWithProjectId(
        updatedBefore: Instant,
        batchLimit: Long
    ): List<ManagedServiceDeployment>
}
