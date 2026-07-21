package io.dockistry.platform.services.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.time.Instant

@Embeddable
class ManagedServiceDeletionContext(
    @Column(name = "deletion_remove_volumes")
    val removeVolumes: Boolean,

    @Column(name = "deletion_requested_at")
    val requestedAt: Instant,

    @Column(name = "deletion_last_cleanup_attempt_at")
    var lastCleanupAttemptAt: Instant? = null,

    @Column(name = "deletion_cleanup_error_message", columnDefinition = "text")
    var cleanupErrorMessage: String? = null
)
