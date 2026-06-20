package io.dockistry.platform.auth.application

import io.dockistry.platform.auth.domain.EmailVerificationChallenge
import io.dockistry.platform.auth.domain.EmailVerificationChallengeReason
import io.dockistry.platform.auth.domain.dto.VerificationCodes
import io.dockistry.platform.auth.infrastructure.EmailVerificationProperties
import io.dockistry.platform.auth.infrastructure.EmailVerificationChallengeRepository
import io.dockistry.platform.auth.infrastructure.VerificationCodeGenerator
import io.dockistry.platform.auth.infrastructure.VerificationCodeHasher
import io.dockistry.platform.iam.infrastructure.UserRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

@Service
class EmailVerificationChallengeIssuer(
    private val emailVerificationChallengeRepository: EmailVerificationChallengeRepository,
    private val userRepository: UserRepository,
    private val clock: Clock,
    private val emailVerificationProperties: EmailVerificationProperties,
    private val verificationCodeHasher: VerificationCodeHasher
) {
    fun issueChallenge(
        userId: UUID,
        reason: EmailVerificationChallengeReason
    ): VerificationCodes {
        val user = userRepository.findUserById(userId)
            ?: throw InternalDataIntegrityException(
                "User with id $userId was passed for" +
                        " email verification challenge issuing, but couldn't be found in DB"
            )

        val now = clock.instant()
        val numberCode = VerificationCodeGenerator.generateCode()
        val linkCode = VerificationCodeGenerator.generateLinkCode()

        val numberCodeHash = verificationCodeHasher.hash(numberCode)
        val linkCodeHash = verificationCodeHasher.hash(linkCode)

        emailVerificationChallengeRepository.findActiveChallenge(user)
            ?.let {
                it.supersededAt = now
                emailVerificationChallengeRepository.flush()
            }

        emailVerificationChallengeRepository.saveAndFlush(
            EmailVerificationChallenge(
                user = user,
                linkCodeHash = linkCodeHash,
                numberCodeHash = numberCodeHash,
                linkExpiresAt = now.plusMillis(
                    emailVerificationProperties.linkCodeExpiration
                ),
                numberExpiresAt = now.plusMillis(
                    emailVerificationProperties.numberCodeExpiration
                ),
                createdAt = now,
                reason = reason
            )
        )

        return VerificationCodes(linkCode, numberCode)
    }
}
