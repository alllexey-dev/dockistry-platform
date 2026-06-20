package io.dockistry.platform.email.application.ports

interface RawEmailSender {
    fun sendEmail(email: String, subject: String, text: String)
}