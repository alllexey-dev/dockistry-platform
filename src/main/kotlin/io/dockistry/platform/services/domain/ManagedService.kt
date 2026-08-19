@file:Suppress("ProtectedInFinal")

package io.dockistry.platform.services.domain

import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import jakarta.persistence.Column
import jakarta.persistence.Embedded
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
import jakarta.persistence.Transient
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "services")
class ManagedService(
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    val project: Project,

    name: String,

    configuration: ManagedServiceConfiguration,

    createdAt: Instant,

    updatedAt: Instant
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    @Column(
        name = "name",
        nullable = false,
        length = ManagedServiceValidator.MAX_NAME_LENGTH
    )
    val name: String = name

    @Column(
        name = "image_repository",
        nullable = false,
        length = ManagedServiceImageRefNormalizer.MAX_REPOSITORY_LENGTH
    )
    var imageRepository: String = configuration.imageRef.repository
        protected set

    @Column(
        name = "image_tag",
        nullable = false,
        length = ManagedServiceImageRefNormalizer.MAX_TAG_LENGTH
    )
    var imageTag: String = configuration.imageRef.tag
        protected set

    @get:Transient
    val imageRef: ManagedServiceImageRef
        get() = ManagedServiceImageRef(imageRepository, imageTag)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "command", columnDefinition = "jsonb")
    var command: List<String>? = configuration.command
        protected set

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "args", columnDefinition = "jsonb")
    var args: List<String>? = configuration.args
        protected set

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "env", nullable = false, columnDefinition = "jsonb")
    var env: Map<String, String> = configuration.env
        protected set

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "endpoints", nullable = false, columnDefinition = "jsonb")
    var endpoints: List<ServiceEndpoint> = configuration.endpoints
        protected set

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "volumes", nullable = false, columnDefinition = "jsonb")
    var volumes: List<ServiceVolumeMount> = configuration.volumes
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "restart_policy", nullable = false)
    var restartPolicy: DockerRestartPolicyType = configuration.restartPolicy
        protected set

    @Column(name = "restart_max_retries")
    var restartMaxRetries: Int? = configuration.restartMaxRetries
        protected set

    @Column(name = "memory_limit_bytes")
    var memoryLimitBytes: Long? = configuration.memoryLimitBytes
        protected set

    @Column(name = "cpu_limit_nano_cpus")
    var cpuLimitNanoCpus: Long? = configuration.cpuLimitNanoCpus
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "desired_state", nullable = false)
    var desiredState: ManagedServiceDesiredState = configuration.desiredState
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false)
    var lifecycleStatus: ManagedServiceLifecycleStatus = ManagedServiceLifecycleStatus.ACTIVE
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = createdAt

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = updatedAt
        protected set

    @Embedded
    var deletionContext: ManagedServiceDeletionContext? = null
        protected set

    fun updateConfiguration(
        configuration: ManagedServiceConfiguration,
        updatedAt: Instant
    ) {
        imageRepository = configuration.imageRef.repository
        imageTag = configuration.imageRef.tag
        command = configuration.command
        args = configuration.args
        env = configuration.env
        endpoints = configuration.endpoints
        volumes = configuration.volumes
        restartPolicy = configuration.restartPolicy
        restartMaxRetries = configuration.restartMaxRetries
        memoryLimitBytes = configuration.memoryLimitBytes
        cpuLimitNanoCpus = configuration.cpuLimitNanoCpus
        desiredState = configuration.desiredState
        this.updatedAt = updatedAt
    }

    fun updateDesiredState(
        state: ManagedServiceDesiredState,
        updatedAt: Instant
    ) {
        desiredState = state
        this.updatedAt = updatedAt
    }

    fun beginStart(updatedAt: Instant) {
        requireLifecycleStatus(ManagedServiceLifecycleStatus.ACTIVE)
        lifecycleStatus = ManagedServiceLifecycleStatus.STARTING
        desiredState = ManagedServiceDesiredState.RUNNING
        this.updatedAt = updatedAt
    }

    fun completeStart(updatedAt: Instant) {
        transitionRuntimeOperationToActive(
            expectedStatus = ManagedServiceLifecycleStatus.STARTING,
            updatedAt = updatedAt
        )
    }

    fun failStart(updatedAt: Instant) {
        transitionRuntimeOperationToActive(
            expectedStatus = ManagedServiceLifecycleStatus.STARTING,
            updatedAt = updatedAt
        )
    }

    fun beginStop(updatedAt: Instant) {
        requireLifecycleStatus(ManagedServiceLifecycleStatus.ACTIVE)
        lifecycleStatus = ManagedServiceLifecycleStatus.STOPPING
        desiredState = ManagedServiceDesiredState.STOPPED
        this.updatedAt = updatedAt
    }

    fun completeStop(updatedAt: Instant) {
        transitionRuntimeOperationToActive(
            expectedStatus = ManagedServiceLifecycleStatus.STOPPING,
            updatedAt = updatedAt
        )
    }

    fun failStop(updatedAt: Instant) {
        transitionRuntimeOperationToActive(
            expectedStatus = ManagedServiceLifecycleStatus.STOPPING,
            updatedAt = updatedAt
        )
    }

    fun beginPause(updatedAt: Instant) {
        requireLifecycleStatus(ManagedServiceLifecycleStatus.ACTIVE)
        lifecycleStatus = ManagedServiceLifecycleStatus.PAUSING
        this.updatedAt = updatedAt
    }

    fun completePause(updatedAt: Instant) {
        transitionRuntimeOperationToActive(
            expectedStatus = ManagedServiceLifecycleStatus.PAUSING,
            updatedAt = updatedAt
        )
    }

    fun failPause(updatedAt: Instant) {
        transitionRuntimeOperationToActive(
            expectedStatus = ManagedServiceLifecycleStatus.PAUSING,
            updatedAt = updatedAt
        )
    }

    fun beginUnpause(updatedAt: Instant) {
        requireLifecycleStatus(ManagedServiceLifecycleStatus.ACTIVE)
        lifecycleStatus = ManagedServiceLifecycleStatus.UNPAUSING
        this.updatedAt = updatedAt
    }

    fun completeUnpause(updatedAt: Instant) {
        transitionRuntimeOperationToActive(
            expectedStatus = ManagedServiceLifecycleStatus.UNPAUSING,
            updatedAt = updatedAt
        )
    }

    fun failUnpause(updatedAt: Instant) {
        transitionRuntimeOperationToActive(
            expectedStatus = ManagedServiceLifecycleStatus.UNPAUSING,
            updatedAt = updatedAt
        )
    }

    fun beginDeletion(updatedAt: Instant) {
        requireLifecycleStatus(ManagedServiceLifecycleStatus.ACTIVE)
        lifecycleStatus = ManagedServiceLifecycleStatus.DELETING
        deletionContext = ManagedServiceDeletionContext(
            requestedAt = updatedAt
        )
        this.updatedAt = updatedAt
    }

    private fun transitionRuntimeOperationToActive(
        expectedStatus: ManagedServiceLifecycleStatus,
        updatedAt: Instant
    ) {
        requireLifecycleStatus(expectedStatus)
        lifecycleStatus = ManagedServiceLifecycleStatus.ACTIVE
        this.updatedAt = updatedAt
    }

    private fun requireLifecycleStatus(expectedStatus: ManagedServiceLifecycleStatus) {
        if (lifecycleStatus != expectedStatus) {
            throw InternalDataIntegrityException(
                "Managed service lifecycle status is $lifecycleStatus instead of " +
                        expectedStatus
            )
        }
    }
}
