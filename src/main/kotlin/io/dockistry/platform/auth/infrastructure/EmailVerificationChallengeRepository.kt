package io.dockistry.platform.auth.infrastructure

import io.dockistry.platform.auth.domain.EmailVerificationChallenge
import io.dockistry.platform.iam.domain.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface EmailVerificationChallengeRepository : JpaRepository<EmailVerificationChallenge, UUID> {
    @Query("""
        SELECT challenge
        FROM EmailVerificationChallenge challenge
        WHERE challenge.user = :user
            AND challenge.consumedAt IS NULL
            AND challenge.supersededAt IS NULL
    """)
    fun findActiveChallenge(user: User): EmailVerificationChallenge?

    @Query("""
        SELECT COUNT(challenge)
        FROM EmailVerificationChallenge challenge
        WHERE challenge.user = :user
            AND challenge.createdAt >= :since
    """)
    fun countCreatedSince(user: User, since: Instant): Long

    fun findByLinkCodeHash(linkCodeHash: String): EmailVerificationChallenge?
}
