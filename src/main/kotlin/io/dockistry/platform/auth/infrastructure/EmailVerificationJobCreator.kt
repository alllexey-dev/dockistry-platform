package io.dockistry.platform.auth.infrastructure

import io.dockistry.platform.auth.domain.EmailVerificationChallengeReason
import io.dockistry.platform.email.domain.EmailSendJob
import io.dockistry.platform.email.domain.EmailSendJobLifecycleService
import io.dockistry.platform.email.domain.EmailSendJobType
import io.dockistry.platform.email.domain.VerificationEmailJobContext
import io.dockistry.platform.email.infrastructure.EmailSendJobRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class EmailVerificationJobCreator(
    private val emailSendJobLifecycleService: EmailSendJobLifecycleService,
    private val emailSendJobRepository: EmailSendJobRepository
) {
    fun enqueueRegisterVerificationJob(userId: UUID): EmailSendJob {
        return enqueueVerificationJob(userId, EmailVerificationChallengeReason.REGISTRATION)
    }

    fun enqueueResendVerificationJob(userId: UUID): EmailSendJob {
        return enqueueVerificationJob(userId, EmailVerificationChallengeReason.RESEND)
    }

    fun enqueueVerificationJob(
        userId: UUID,
        reason: EmailVerificationChallengeReason
    ): EmailSendJob {
        return emailSendJobRepository.findActiveEmailVerificationJobForUser(userId)
            ?: createVerificationJob(userId, reason)
    }

    private fun createVerificationJob(
        userId: UUID,
        reason: EmailVerificationChallengeReason
    ): EmailSendJob {
        return try {
            emailSendJobLifecycleService.create(
                jobType = EmailSendJobType.EMAIL_VERIFICATION,
                jobContext = VerificationEmailJobContext(
                    userId = userId,
                    reason = reason
                )
            )
        } catch (exception: DataIntegrityViolationException) {
            emailSendJobRepository.findActiveEmailVerificationJobForUser(userId)
                ?: throw exception
        }
    }
}
