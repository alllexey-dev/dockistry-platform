package io.dockistry.platform.auth.application.ports

interface EmailSenderService {
    fun sendEmail(email: String, subject: String, text: String)
}
