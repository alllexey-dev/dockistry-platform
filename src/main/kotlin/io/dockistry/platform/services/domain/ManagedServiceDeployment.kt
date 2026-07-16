package io.dockistry.platform.services.domain

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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "service_deployments")
class ManagedServiceDeployment(
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    val service: ManagedService,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_snapshot", nullable = false, columnDefinition = "jsonb")
    val configSnapshot: ManagedServiceConfiguration,

    @Column(name = "service_version", nullable = false)
    val serviceVersion: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    @Column(name = "container_id")
    var containerId: String? = null

    @Column(
        name = "image_repository",
        nullable = false,
        length = ManagedServiceImageRefNormalizer.MAX_REPOSITORY_LENGTH
    )
    val imageRepository: String = configSnapshot.imageRef.repository

    @Column(
        name = "image_tag",
        nullable = false,
        length = ManagedServiceImageRefNormalizer.MAX_TAG_LENGTH
    )
    val imageTag: String = configSnapshot.imageRef.tag

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ManagedServiceDeploymentStatus = ManagedServiceDeploymentStatus.IN_PROCESS

    @Column(name = "error_message", columnDefinition = "text")
    var errorMessage: String? = null

    fun finish(containerId: String, updatedAt: Instant) {
        check(status == ManagedServiceDeploymentStatus.IN_PROCESS)

        this.containerId = containerId
        status = ManagedServiceDeploymentStatus.FINISHED
        this.updatedAt = updatedAt
    }

    fun fail(errorMessage: String?, updatedAt: Instant) {
        check(status == ManagedServiceDeploymentStatus.IN_PROCESS)

        status = ManagedServiceDeploymentStatus.FAILED
        this.errorMessage = errorMessage
        this.updatedAt = updatedAt
    }
}

enum class ManagedServiceDeploymentStatus {
    IN_PROCESS,
    FINISHED,
    STOPPED,
    FAILED,
    REMOVED
}
