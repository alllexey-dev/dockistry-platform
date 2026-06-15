package io.dockistry.platform.auth.domain.dto

import jakarta.validation.constraints.NotBlank

data class RefreshRequest(
    @field:NotBlank
    val refreshToken: String
)