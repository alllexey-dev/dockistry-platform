package io.dockistry.platform.auth.domain.dto

data class VerifyEmailByNumberCodeRequest(
    val numberCode: String,
    val email: String
)