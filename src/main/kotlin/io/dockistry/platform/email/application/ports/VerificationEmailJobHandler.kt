package io.dockistry.platform.email.application.ports

import io.dockistry.platform.auth.application.EmailVerificationChallengeIssuer
import io.dockistry.platform.auth.domain.EmailVerificationChallengeReason
import io.dockistry.platform.auth.infrastructure.EmailVerificationLinks
import io.dockistry.platform.email.domain.VerificationEmailJobContext
import io.dockistry.platform.iam.infrastructure.UserRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class VerificationEmailJobHandler(
    private val emailVerificationChallengeIssuer: EmailVerificationChallengeIssuer,
    private val userRepository: UserRepository,
    private val senderService: RawEmailSender,
    private val emailVerificationLinks: EmailVerificationLinks
) : EmailJobHandler<VerificationEmailJobContext> {

    companion object {
        const val EMAIL_SUBJECT = "Dockistry Email Verification"

        fun buildEmailBody(
            numberCode: String,
            linkCode: String,
            emailVerificationLinks: EmailVerificationLinks
        ): String {
            return """
                Welcome to Dockistry!

                Your verification code: $numberCode
                Alternatively, you can activate it by following link:
                ${emailVerificationLinks.buildVerificationLink(linkCode)}
            """.trimIndent()
        }
    }

    @Transactional
    override fun handle(context: VerificationEmailJobContext) {
        val userId = context.userId

        val verificationCodes = emailVerificationChallengeIssuer.issueChallenge(
            userId,
            context.reason
        )

        val user =
            userRepository.findUserById(userId) ?: throw InternalDataIntegrityException(
                "User with id $userId was passed for" +
                        " email verification challenge issuing, but couldn't be found in DB"
            )

        senderService.sendEmail(
            email = user.email,
            subject = EMAIL_SUBJECT,
            text = buildEmailBody(
                numberCode = verificationCodes.numberCode,
                linkCode = verificationCodes.linkCode,
                emailVerificationLinks
            )
        )
    }
}
