package io.dockistry.platform.secrets.domain

import io.dockistry.platform.services.domain.ManagedService
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.util.UUID

@Entity
@Table(
    name = "managed_service_secret_attachments",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uc_service_secret_attachment_reference",
            columnNames = ["service_id", "secret_id", "secret_key", "secret_version"]
        )
    ]
)
class ManagedServiceSecretAttachment(
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    val service: ManagedService,

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "secret_id", nullable = false)
    val secret: Secret,

    @Column(name = "secret_key", nullable = false, length = 255)
    val secretKey: String,

    @Column(name = "secret_version", length = 16)
    val secretVersion: String? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}
