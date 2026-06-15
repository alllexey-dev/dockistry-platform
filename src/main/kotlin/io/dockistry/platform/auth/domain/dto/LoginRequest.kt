package io.dockistry.platform.auth.domain.dto

import jakarta.validation.constraints.Pattern

data class LoginRequest(
    @field:Pattern(
        regexp = "^[a-zA-Z0-9._-]{3,32}$",
        message = "Username must consist of 3-32 English letters, digits, dots, underscores, dashes"
    )
    val username: String,

    @field:Pattern(
        // All printable characters from ASCII table
        regexp = "^[\\x20-\\x7E]{8,128}$",
        message = "Password must be 8-128 printable ASCII characters"
    )
    val password: String
)