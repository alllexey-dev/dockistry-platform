package io.dockistry.platform.auth.domain

import io.dockistry.platform.iam.domain.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.Email
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "email_verification_requests")
class EmailVerificationRequest(
    @field:Email
    @Column(nullable = false, unique = true)
    val email: String,

    @Column(name = "link_code_hash", nullable = false)
    val linkCodeHash: String,

    @Column(name = "number_code_hash", nullable = false)
    val numberCodeHash: String,

    @Column(name = "link_expires_at", nullable = false)
    val linkExpiresAt: Instant,

    @Column(name = "number_expires_at", nullable = false)
    val numberExpiresAt: Instant,

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null,

    @Column(name = "attempts_count", nullable = false)
    var attemptsCount: Int = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User
}
