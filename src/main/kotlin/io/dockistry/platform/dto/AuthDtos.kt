package io.dockistry.platform.dto

data class LoginRequest(
    val username: String,
    val password: String
)

data class RegisterRequest(
    val email: String,
    val username: String,
    val password: String
)

data class RefreshRequest(
    val refreshToken: String
)

data class AuthResponse(
    val refreshToken: String,
    val refreshExpiresAt: Long,
    val accessToken: String,
    val accessExpiresAt: Long
)

data class TokenPair(
    val refreshToken: String,
    val refreshExpiresAt: Long,
    val accessToken: String,
    val accessExpiresAt: Long
)