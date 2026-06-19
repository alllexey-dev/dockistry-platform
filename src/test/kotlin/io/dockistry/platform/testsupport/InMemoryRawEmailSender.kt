package io.dockistry.platform.testsupport

import io.dockistry.platform.email.application.ports.RawEmailSender
import java.util.concurrent.ConcurrentLinkedQueue

class InMemoryRawEmailSender : RawEmailSender {
    private val sentEmails = ConcurrentLinkedQueue<SentEmail>()

    override fun sendEmail(email: String, subject: String, text: String) {
        sentEmails += SentEmail(
            to = email,
            subject = subject,
            text = text
        )
    }

    fun latestTo(email: String): SentEmail? {
        return sentEmails.lastOrNull { it.to == email }
    }

    fun clear() {
        sentEmails.clear()
    }
}

data class SentEmail(
    val to: String,
    val subject: String,
    val text: String
)
