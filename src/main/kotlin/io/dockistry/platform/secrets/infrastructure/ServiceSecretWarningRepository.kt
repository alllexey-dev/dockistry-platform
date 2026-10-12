package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.secrets.domain.ServiceSecretWarning
import io.dockistry.platform.services.domain.ManagedService
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface ServiceSecretWarningRepository : JpaRepository<ServiceSecretWarning, UUID> {
    fun findByServiceAndResolvedAtIsNullOrderByCreatedAtAsc(
        service: ManagedService
    ): List<ServiceSecretWarning>

    @Query("""
        SELECT warning
        FROM ServiceSecretWarning warning
        WHERE warning.resolvedAt IS NULL
            AND warning.service.id IN (:serviceIds)
        ORDER BY warning.createdAt ASC, warning.id ASC
    """)
    fun findActiveByServiceIds(serviceIds: List<UUID>): List<ServiceSecretWarning>

    /**
     * Keeps one active warning per target. A repeated event re-identifies the existing row
     * instead of being dropped: a deploy resolves only the warning IDs it accepted in `begin`,
     * so an event that arrives later must not hide behind an already accepted ID.
     */
    @Query("""
        INSERT INTO service_secret_warnings
            (id, service_id, secret_id, secret_key, secret_version,
                warning_type, env_name, created_at, resolved_at)
        VALUES (gen_random_uuid(), :serviceId, :secretId, :secretKey,
            :secretVersion, :warningType, :envName, :createdAt, NULL)
        ON CONFLICT (service_id, warning_type, secret_id, secret_key,
            secret_version, env_name)
            WHERE resolved_at IS NULL
            DO UPDATE SET id = gen_random_uuid(), created_at = EXCLUDED.created_at
    """, nativeQuery = true)
    @Modifying
    fun saveOrRefreshActive(
        serviceId: UUID,
        warningType: String,
        secretId: String,
        secretKey: String,
        secretVersion: String?,
        envName: String,
        createdAt: Instant
    ): Int

    fun saveOrRefreshActive(warning: ServiceSecretWarning): Int = saveOrRefreshActive(
        serviceId = warning.service.id,
        warningType = warning.warningType.name,
        secretId = warning.secretId,
        secretKey = warning.secretKey,
        secretVersion = warning.secretVersion,
        envName = warning.envName,
        createdAt = warning.createdAt
    )

    @Query("""
        UPDATE ServiceSecretWarning warning
        SET warning.resolvedAt = :now
        WHERE warning.id IN (:warningIds)
            AND warning.resolvedAt IS NULL
    """)
    @Modifying
    fun saveResolvedBulk(warningIds: List<UUID>, now: Instant): Int
}
