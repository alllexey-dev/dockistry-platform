package io.dockistry.platform.auth.infrastructure

import io.dockistry.platform.auth.domain.EmailVerificationChallengeReason
import io.dockistry.platform.email.domain.EmailSendJob
import io.dockistry.platform.email.domain.EmailSendJobLifecycleService
import io.dockistry.platform.email.domain.VerificationEmailJobContext
import io.dockistry.platform.email.infrastructure.EmailSendJobRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
class EmailVerificationJobCreator(
    private val emailSendJobLifecycleService: EmailSendJobLifecycleService,
    private val emailSendJobRepository: EmailSendJobRepository,
    private val objectMapper: ObjectMapper
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
            val context = VerificationEmailJobContext(
                userId = userId,
                reason = reason
            )

            emailSendJobLifecycleService.create(
                contextTypeKey = VerificationEmailJobContext.TYPE_KEY,
                jobContext = serializeContext(context)
            )
        } catch (exception: DataIntegrityViolationException) {
            emailSendJobRepository.findActiveEmailVerificationJobForUser(userId)
                ?: throw exception
        }
    }

    private fun serializeContext(context: VerificationEmailJobContext): String {
        return try {
            objectMapper.writeValueAsString(context)
        } catch (exception: RuntimeException) {
            throw InternalDataIntegrityException(
                "Email verification job context could not be serialized: ${exception.message}"
            )
        }
    }
}
