package io.dockistry.platform.email.infrastructure

import io.dockistry.platform.email.application.EmailSendJobProcessor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class EmailSendJobScheduler(private val emailSendJobProcessor: EmailSendJobProcessor) {

    companion object {
        private val logger: Logger =
            LoggerFactory.getLogger(EmailSendJobScheduler::class.java)
    }

    @Scheduled(fixedDelayString = $$"${app.email-send-job.poll-interval}")
    fun processEmailJobs() {
        runCatching {
            emailSendJobProcessor.processEmailSendJobs()
        }.onFailure {
            logger.error("Failed to process email jobs: {}", it.message, it)
        }
    }
}