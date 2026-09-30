package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.secrets.domain.ManagedServiceSecretReferenceEntity
import io.dockistry.platform.services.domain.ManagedService
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ManagedServiceSecretReferenceRepository :
    JpaRepository<ManagedServiceSecretReferenceEntity, UUID> {

    @Modifying
    @Query("""
            DELETE FROM ManagedServiceSecretReferenceEntity reference
            WHERE reference.service = :service
        """)
    fun deleteByService(service: ManagedService): Int

    fun findByServiceId(serviceId: UUID): List<ManagedServiceSecretReferenceEntity>

    fun findByServiceIdIn(serviceIds: Collection<UUID>): List<ManagedServiceSecretReferenceEntity>

    /* reference.service FETCH JOIN is required to avoid N+1 in SecretService.rotate */
    @Query("""
        SELECT reference
        FROM ManagedServiceSecretReferenceEntity reference
        JOIN FETCH reference.service
        WHERE reference.secret.id = :secretId
    """)
    fun findBySecretId(secretId: String): List<ManagedServiceSecretReferenceEntity>

    @Query("""
        SELECT reference
        FROM ManagedServiceSecretReferenceEntity reference
        WHERE reference.service.project.id = :projectId
            AND reference.secret.id = :secretId
    """)
    fun findByProjectAndSecret(projectId: UUID, secretId: String): List<ManagedServiceSecretReferenceEntity>
}
