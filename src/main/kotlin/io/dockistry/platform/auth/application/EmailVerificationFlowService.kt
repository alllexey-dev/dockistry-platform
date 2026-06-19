package io.dockistry.platform.auth.application

import io.dockistry.platform.auth.domain.EmailVerificationChallenge
import io.dockistry.platform.auth.domain.EmailVerificationChallengeReason
import io.dockistry.platform.auth.domain.InvalidLinkCodeVerificationException
import io.dockistry.platform.auth.domain.InvalidNumberCodeVerificationException
import io.dockistry.platform.auth.infrastructure.EmailVerificationJobCreator
import io.dockistry.platform.auth.infrastructure.EmailVerificationProperties
import io.dockistry.platform.auth.infrastructure.EmailVerificationChallengeRepository
import io.dockistry.platform.auth.infrastructure.VerificationCodeHasher
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.iam.infrastructure.UserRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class EmailVerificationFlowService(
    private val userRepository: UserRepository,
    private val challengeRepository: EmailVerificationChallengeRepository,
    private val clock: Clock,
    private val verificationProperties: EmailVerificationProperties,
    private val verificationCodeHasher: VerificationCodeHasher,
    private val throttleService: EmailVerificationThrottleService,
    private val emailVerificationJobCreator: EmailVerificationJobCreator
) {

    companion object {
        private val logger: Logger =
            LoggerFactory.getLogger(EmailVerificationFlowService::class.java)
    }

    /**
     * Verifies email by number code and returns user ID.
     */
    @Transactional
    fun verifyNumberCode(email: String, numberCode: String): UUID {
        val user = userRepository.findByEmail(email)

        if (user == null) {
            logger.warn("Email number-code verification encountered for an unregistered email: $email")
            throw InvalidNumberCodeVerificationException()
        }

        val challenge = challengeRepository.findActiveChallenge(user)
        if (challenge == null) {
            logger.warn("Email number-code verification encountered for user with no active verification challenges: $email")
            throw InvalidNumberCodeVerificationException()
        }

        if (!isChallengeActive(challenge) || isNumberCodeExpired(challenge)) {
            logger.warn("Email number-code verification encountered for inactive or expired challenge: ${challenge.id}")
            throw InvalidNumberCodeVerificationException()
        }

        if (!verificationCodeHasher.matches(numberCode, challenge.numberCodeHash)) {
            logger.warn("Invalid number-code verification attempt for user ${user.id}")
            throw InvalidNumberCodeVerificationException()
        }

        val now = clock.instant()
        challenge.consumedAt = now
        user.emailVerifiedAt = now

        return user.id
    }


    /**
     * Verifies email by link code and returns user ID.
     */
    @Transactional
    fun verifyLinkCode(linkCode: String): UUID {
        val actualLinkCodeHash = verificationCodeHasher.hash(linkCode)
        val challenge = challengeRepository.findByLinkCodeHash(actualLinkCodeHash)
            ?: throw InvalidLinkCodeVerificationException()

        if (!isChallengeActive(challenge) || isLinkCodeExpired(challenge)) {
            logger.warn(
                "Email verification encountered for inactive or expired link-code. " +
                        "EmailVerificationChallenge ID: ${challenge.id}"
            )
            throw InvalidLinkCodeVerificationException()
        }

        val now = clock.instant()
        challenge.consumedAt = now
        challenge.user.emailVerifiedAt = now

        return challenge.user.id
    }

    @Transactional
    fun requestVerificationCodes(email: String, reason: EmailVerificationChallengeReason) {
        val candidate = findVerificationCodeSendCandidate(email) ?: return
        if (throttleService.isBlocked(candidate.user)) return

        if (isCooldownActive(candidate.lastChallenge)) {
            logger.warn("Not sending verification codes due to cooldown for email: $email")
            return
        }

        if (isRateLimitActive(candidate.user)) {
            throttleService.throttle(candidate.user)
            logger.warn("Throttling verification codes sending due to exceeding rate limit for email: $email")
            return
        }
        enqueueVerificationEmail(candidate, reason)
    }

    private fun enqueueVerificationEmail(
        candidate: VerificationCodeSendCandidate,
        reason: EmailVerificationChallengeReason
    ) {
        candidate.lastChallenge?.supersededAt = clock.instant()

        emailVerificationJobCreator.enqueueVerificationJob(candidate.user.id, reason)
    }

    private fun findVerificationCodeSendCandidate(email: String): VerificationCodeSendCandidate? {
        val user = userRepository.findByEmail(email)

        if (user == null) {
            logger.warn("Verification code send requested for an unregistered email: $email")
            return null
        } else if (user.emailVerifiedAt != null) {
            logger.warn("Verification code send requested for already verified email: $email")
            return null
        }

        val lastChallenge = challengeRepository.findActiveChallenge(user)
        return VerificationCodeSendCandidate(user, lastChallenge)
    }

    private fun isCooldownActive(lastChallenge: EmailVerificationChallenge?): Boolean {
        val lastChallengeTime = lastChallenge?.createdAt ?: return false

        return (lastChallengeTime > clock.instant()
            .minusMillis(verificationProperties.resendCooldown))
    }

    private fun isRateLimitActive(user: User): Boolean {
        val count = challengeRepository.countCreatedSince(
            user = user,
            since = clock.instant()
                .minusMillis(verificationProperties.rateLimitWindow)
        )
        return count >= verificationProperties.rateLimit
    }

    private fun isChallengeActive(challenge: EmailVerificationChallenge): Boolean {
        return challenge.consumedAt == null && challenge.supersededAt == null
    }

    private fun isNumberCodeExpired(challenge: EmailVerificationChallenge): Boolean {
        return clock.instant() >= challenge.numberExpiresAt
    }

    private fun isLinkCodeExpired(challenge: EmailVerificationChallenge): Boolean {
        return clock.instant() >= challenge.linkExpiresAt
    }

    private data class VerificationCodeSendCandidate(
        val user: User,
        val lastChallenge: EmailVerificationChallenge?
    )
}
