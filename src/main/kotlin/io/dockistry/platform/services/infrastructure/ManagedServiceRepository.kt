package io.dockistry.platform.services.infrastructure

import io.dockistry.platform.projects.application.ports.ProjectManagedServicesExistencePort
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceLifecycleStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface ManagedServiceRepository :
    JpaRepository<ManagedService, UUID>,
    ProjectManagedServicesExistencePort,
    ManagedServiceWaitingLockRepository {
    fun existsByProjectIdAndName(projectId: UUID, name: String): Boolean

    fun findAllByProjectIdAndLifecycleStatusIn(
        projectId: UUID,
        lifecycleStatuses: Collection<ManagedServiceLifecycleStatus>
    ): List<ManagedService>

    fun findAllByProjectId(projectId: UUID): List<ManagedService>

    @Query("""
        SELECT EXISTS (
            SELECT service
            FROM ManagedService service
            WHERE service.project.id = :projectId
        )
    """)
    override fun existsByProjectId(projectId: UUID): Boolean

    // Checking not only by PK, but also by Project ID, so that at the same time we're
    // also checking that this service belongs to specified project
    @Query("""
        SELECT service
            FROM ManagedService service
            WHERE service.project.id = :projectId
              AND service.id = :serviceId
    """)
    fun findByProjectIdAndId(
        projectId: UUID,
        serviceId: UUID
    ): ManagedService?

    @Query(
        value = """
            SELECT *
            FROM services
            WHERE id = :serviceId
            FOR NO KEY UPDATE NOWAIT
        """,
        nativeQuery = true
    )
    fun findByIdForUpdateNowait(
        serviceId: UUID
    ): ManagedService?

    @Query(
        value = """
            SELECT *
            FROM services
            WHERE project_id = :projectId
              AND id = :serviceId
            FOR NO KEY UPDATE NOWAIT
        """,
        nativeQuery = true
    )
    fun findByProjectIdAndIdForUpdateNowait(
        projectId: UUID,
        serviceId: UUID
    ): ManagedService?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        DELETE FROM ManagedService service
            WHERE service.project.id = :projectId
              AND service.id = :serviceId
              AND service.lifecycleStatus = ManagedServiceLifecycleStatus.DELETING
    """)
    fun deleteDeletingByProjectIdAndId(
        projectId: UUID,
        serviceId: UUID
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ManagedService service
        SET service.deletionContext.lastCleanupAttemptAt = :attemptedAt,
            service.deletionContext.cleanupErrorMessage = :errorMessage,
            service.updatedAt = :attemptedAt,
            service.version = service.version + 1
        WHERE service.project.id = :projectId
          AND service.id = :serviceId
          AND service.lifecycleStatus = ManagedServiceLifecycleStatus.DELETING
    """)
    fun markDeletionCleanupFailedIfDeleting(
        projectId: UUID,
        serviceId: UUID,
        errorMessage: String?,
        attemptedAt: Instant
    ): Int

    @Query(
        value = """
            SELECT id, version
            FROM services
            WHERE lifecycle_status IN (
                'STARTING',
                'STOPPING',
                'PAUSING',
                'UNPAUSING'
            )
              AND updated_at < :updatedBefore
            ORDER BY updated_at
            LIMIT :batchLimit
        """,
        nativeQuery = true
    )
    fun findStuckRuntimeOperations(
        updatedBefore: Instant,
        batchLimit: Long
    ): List<StuckManagedServiceRuntimeOperation>

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ManagedService service
        SET service.lifecycleStatus = ManagedServiceLifecycleStatus.ACTIVE,
            service.updatedAt = :updatedAt,
            service.version = service.version + 1
        WHERE service.id = :serviceId
          AND service.version = :expectedVersion
          AND service.lifecycleStatus IN (
              ManagedServiceLifecycleStatus.STARTING,
              ManagedServiceLifecycleStatus.STOPPING,
              ManagedServiceLifecycleStatus.PAUSING,
              ManagedServiceLifecycleStatus.UNPAUSING
          )
    """)
    fun setRuntimeOperationToActiveIfVersion(
        serviceId: UUID,
        expectedVersion: Long,
        updatedAt: Instant
    ): Int

    @Query("""
        SELECT service
        FROM ManagedService service
        JOIN FETCH service.project
        WHERE service.lifecycleStatus = ManagedServiceLifecycleStatus.DELETING
          AND service.updatedAt < :updatedBefore
        ORDER BY service.updatedAt
        LIMIT :batchLimit
    """)
    fun findStuckDeletingServices(
        updatedBefore: Instant,
        batchLimit: Long
    ): List<ManagedService>
}

interface StuckManagedServiceRuntimeOperation {
    val id: UUID
    val version: Long
}
