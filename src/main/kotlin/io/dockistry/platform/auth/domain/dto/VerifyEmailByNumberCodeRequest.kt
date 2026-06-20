package io.dockistry.platform.auth.domain.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class VerifyEmailByNumberCodeRequest(
    @field:NotBlank
    @field:Pattern(
        regexp = "^\\d{6}$",
        message = "Number code must consist of 6 digits"
    )
    val numberCode: String,

    @field:NotBlank
    @field:Email
    val email: String
)
