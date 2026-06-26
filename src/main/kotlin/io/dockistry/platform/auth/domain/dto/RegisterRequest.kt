package io.dockistry.platform.auth.domain.dto

data class RegisterRequest(
    val email: String,
    val username: String,
    val password: String
)