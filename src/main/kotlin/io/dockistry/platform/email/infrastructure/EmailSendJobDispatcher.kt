package io.dockistry.platform.email.infrastructure

import io.dockistry.platform.email.application.ports.VerificationEmailJobHandler
import io.dockistry.platform.email.domain.EmailSendJobContext
import io.dockistry.platform.email.domain.VerificationEmailJobContext
import org.springframework.stereotype.Service

@Service
class EmailSendJobDispatcher(
    private val verificationEmailJobHandler: VerificationEmailJobHandler
) {
    fun dispatch(context: EmailSendJobContext) {
        when (context) {
            is VerificationEmailJobContext ->
                verificationEmailJobHandler.handle(context)
        }
    }
}