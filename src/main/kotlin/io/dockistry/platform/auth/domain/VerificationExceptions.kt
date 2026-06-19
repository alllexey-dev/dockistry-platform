package io.dockistry.platform.auth.domain

import io.dockistry.platform.shared.exception.ForbiddenException

class InvalidNumberCodeVerificationException() : ForbiddenException(
    message = "Email or verification code is invalid",
    code = "email_or_verification_code_invalid"
)

class InvalidLinkCodeVerificationException() : ForbiddenException(
    message = "Verification link is invalid",
    code = "verification_link_invalid"
)
