package io.dockistry.platform.auth.application

import io.dockistry.platform.auth.domain.EmailVerificationThrottle
import io.dockistry.platform.auth.infrastructure.EmailVerificationProperties
import io.dockistry.platform.auth.infrastructure.EmailVerificationThrottleRepository
import io.dockistry.platform.iam.domain.User
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class EmailVerificationThrottleService(
    private val throttleRepository: EmailVerificationThrottleRepository,
    private val clock: Clock,
    private val emailVerificationProperties: EmailVerificationProperties
) {
    fun isBlocked(user: User): Boolean {
        return throttleRepository.existsActiveUserBlock(user, clock.instant())
    }

    fun throttle(user: User) {
        val now = clock.instant()
        throttleRepository.save(
            EmailVerificationThrottle(
                user = user,
                blockedUntil = now.plusMillis(
                    emailVerificationProperties.rateLimitThrottleInterval
                ),
                createdAt = now
            )
        )
    }
}