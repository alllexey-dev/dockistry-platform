package io.dockistry.platform.auth.domain

import io.dockistry.platform.iam.domain.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "refresh_tokens")
class RefreshToken(
    @Id
    val id: UUID,

    @Column(nullable = false)
    val issuedAt: Instant,

    @Column(nullable = false)
    val expiresAt: Instant,

    var revokedAt: Instant? = null,

    // Prevents data races when several requests try to refresh one token
    @Version
    var version: Long? = null
) {
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User
}