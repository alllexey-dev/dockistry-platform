package io.dockistry.platform.testsupport

import io.dockistry.platform.email.application.ports.RawEmailSender
import java.util.concurrent.ConcurrentLinkedQueue

class InMemoryRawEmailSender : RawEmailSender {
    private val sentEmails = ConcurrentLinkedQueue<SentEmail>()
    @Volatile
    private var failure: RuntimeException? = null

    override fun sendEmail(email: String, subject: String, text: String) {
        failure?.let { throw it }

        sentEmails += SentEmail(
            to = email,
            subject = subject,
            text = text
        )
    }

    fun latestTo(email: String): SentEmail? {
        return sentEmails.lastOrNull { it.to == email }
    }

    fun allTo(email: String): List<SentEmail> {
        return sentEmails.filter { it.to == email }
    }

    fun clear() {
        sentEmails.clear()
        failure = null
    }

    fun failWith(exception: RuntimeException) {
        failure = exception
    }

    fun resumeSending() {
        failure = null
    }
}

data class SentEmail(
    val to: String,
    val subject: String,
    val text: String
)
