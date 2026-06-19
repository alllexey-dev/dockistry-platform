package io.dockistry.platform.auth.infrastructure

import io.dockistry.platform.auth.domain.EmailVerificationThrottle
import io.dockistry.platform.iam.domain.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface EmailVerificationThrottleRepository : JpaRepository<EmailVerificationThrottle, UUID> {
    @Query("""
        SELECT COUNT(throttle) > 0
        FROM EmailVerificationThrottle throttle
        WHERE throttle.user = :user
            AND :now < throttle.blockedUntil
    """)
    fun existsActiveUserBlock(user: User, now: Instant): Boolean
}