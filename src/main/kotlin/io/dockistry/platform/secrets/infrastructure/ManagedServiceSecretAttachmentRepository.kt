package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.secrets.domain.ManagedServiceSecretAttachment
import io.dockistry.platform.services.domain.ManagedService
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ManagedServiceSecretAttachmentRepository : JpaRepository<ManagedServiceSecretAttachment, UUID> {
    @Modifying
    @Query("""
            DELETE FROM ManagedServiceSecretAttachment attachment
            WHERE attachment.service = :service
        """)
    fun deleteByService(@Param("service") service: ManagedService): Int
}
