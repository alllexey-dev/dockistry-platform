package io.dockistry.platform.auth.domain.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class ResendEmailVerificationRequest(
    @field:NotBlank
    @field:Email
    val email: String
)
