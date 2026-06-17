package io.dockistry.platform.auth.infrastructure

import io.dockistry.platform.auth.application.ports.EmailSenderService

class DummyEmailSenderService : EmailSenderService {
    override fun sendEmail(email: String, subject: String, text: String) {
        val message = """
            Sent-to: $email
            Subject: $subject
            Contents:
            $text
        """.trimIndent()

        println(message)
    }
}