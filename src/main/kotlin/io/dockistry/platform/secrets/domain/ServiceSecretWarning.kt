package io.dockistry.platform.secrets.domain

import io.dockistry.platform.services.domain.ManagedService
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "service_secret_warnings")
class ServiceSecretWarning(
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    val service: ManagedService,

    @Column(name = "secret_id", nullable = false, length = 16)
    val secretId: String,

    @Column(name = "secret_key", nullable = false, length = 255)
    val secretKey: String,

    @Column(name = "secret_version", length = 16)
    val secretVersion: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "warning_type", nullable = false, length = 32)
    val warningType: SecretWarningType,

    @Column(name = "description", nullable = false, columnDefinition = "text")
    val description: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}
