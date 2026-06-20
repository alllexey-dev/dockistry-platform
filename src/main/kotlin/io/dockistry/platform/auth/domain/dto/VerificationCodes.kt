package io.dockistry.platform.auth.domain.dto

data class VerificationCodes(
    val linkCode: String,
    val numberCode: String
)
