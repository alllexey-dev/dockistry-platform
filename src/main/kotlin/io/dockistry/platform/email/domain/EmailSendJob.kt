package io.dockistry.platform.email.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "email_send_jobs")
class EmailSendJob(
    @Column(name = "context_type_key", nullable = false)
    val contextTypeKey: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "job_context", nullable = false, columnDefinition = "jsonb")
    val jobContext: String,

    @Enumerated(value = EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: EmailSendJobStatus = EmailSendJobStatus.PENDING,

    @Column(name = "attempts_count", nullable = false)
    var attemptsCount: Int = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant? = null

    @Column(name = "sent_at")
    var sentAt: Instant? = null

    /**
     * Timestamp when a job handler claimed this job for processing.
     */
    @Column(name = "locked_at")
    var lockedAt: Instant? = null

    /**
     * Timestamp by which the job handler (which took this job) must finish processing this job.
     */
    @Column(name = "locked_until")
    var lockedUntil: Instant? = null

    @Column(name = "last_error")
    var lastError: String? = null
}
