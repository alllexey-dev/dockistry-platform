package io.dockistry.platform.email.infrastructure

import io.dockistry.platform.email.application.ports.RawEmailSender

class DummyRawEmailSender : RawEmailSender {
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