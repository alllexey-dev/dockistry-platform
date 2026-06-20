package io.dockistry.platform.email.application

import io.dockistry.platform.email.config.EmailSendJobConfig
import io.dockistry.platform.email.domain.EmailSendJobLifecycleService
import io.dockistry.platform.email.infrastructure.EmailSendJobClaimer
import io.dockistry.platform.email.infrastructure.EmailSendJobDispatcher
import io.dockistry.platform.email.infrastructure.EmailSendJobRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

@Service
class EmailSendJobProcessor(
    private val jobClaimer: EmailSendJobClaimer,
    private val jobRepository: EmailSendJobRepository,
    private val jobDispatcher: EmailSendJobDispatcher,
    private val jobLifecycleService: EmailSendJobLifecycleService,
    private val jobConfig: EmailSendJobConfig,
    private val clock: Clock
) {
    fun processEmailSendJobs() {
        val jobs =
            jobClaimer.claimDueJobs(
                clock.instant(),
                clock.instant().plusMillis(jobConfig.workerLockingExpiration),
                jobConfig.workerJobsLimit
            )

        jobs.forEach { processEmailSendJob(it) }
    }

    fun processEmailSendJob(emailSendJobId: UUID) {
        val job = jobRepository.findById(emailSendJobId)
            .orElseThrow {
                InternalDataIntegrityException(
                    "Email send job id $emailSendJobId was passed for processing, but couldn't be found in DB"
                )
            }

        try {
            jobDispatcher.dispatch(job.contextTypeKey, job.jobContext)
            jobLifecycleService.markSent(job)
        } catch (e: Exception) {
            jobLifecycleService.markFailed(job, e)
        }
    }
}
