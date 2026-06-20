package io.dockistry.platform.email.domain

import io.dockistry.platform.email.config.EmailSendJobConfig
import io.dockistry.platform.email.infrastructure.EmailSendJobRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class EmailSendJobLifecycleService(
    private val emailSendJobRepository: EmailSendJobRepository,
    private val clock: Clock,
    private val emailSendJobConfig: EmailSendJobConfig
) {
    @Transactional
    fun create(
        contextTypeKey: String,
        jobContext: String
    ): EmailSendJob {
        return emailSendJobRepository.saveAndFlush(
            EmailSendJob(
                contextTypeKey = contextTypeKey,
                jobContext = jobContext,
                createdAt = clock.instant()
            )
        )
    }

    fun markSent(emailSendJob: EmailSendJob) {
        emailSendJob.sentAt = clock.instant()
        emailSendJob.status = EmailSendJobStatus.SENT

        emailSendJobRepository.save(emailSendJob)
    }

    fun markFailed(emailSendJob: EmailSendJob, exception: Exception) {
        emailSendJob.attemptsCount++
        emailSendJob.status = EmailSendJobStatus.FAILED
        emailSendJob.lastError = exception.message
        emailSendJob.nextAttemptAt =
            clock.instant().plusMillis(emailSendJobConfig.retryInterval)

        emailSendJobRepository.save(emailSendJob)
    }
}
