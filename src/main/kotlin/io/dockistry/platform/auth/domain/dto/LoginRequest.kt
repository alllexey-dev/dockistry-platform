package io.dockistry.platform.auth.domain.dto

data class LoginRequest(
    val username: String,
    val password: String
)