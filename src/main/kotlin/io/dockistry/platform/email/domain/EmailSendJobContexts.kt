package io.dockistry.platform.email.domain

import io.dockistry.platform.auth.domain.EmailVerificationChallengeReason
import java.util.UUID

sealed interface EmailSendJobContext

data class VerificationEmailJobContext(
    val userId: UUID,
    val reason: EmailVerificationChallengeReason
) : EmailSendJobContext {
    companion object {
        const val TYPE_KEY = "email_verification"
    }
}
