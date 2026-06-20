package io.dockistry.platform.auth.domain

import io.dockistry.platform.iam.domain.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "email_verification_throttles")
class EmailVerificationThrottle(
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(name = "blocked_until", nullable = false)
    val blockedUntil: Instant,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}