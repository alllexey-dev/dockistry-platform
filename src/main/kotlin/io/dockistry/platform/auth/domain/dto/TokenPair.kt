package io.dockistry.platform.auth.domain.dto

data class TokenPair(
    val refreshToken: String,
    val refreshExpiresAt: Long,
    val accessToken: String,
    val accessExpiresAt: Long
)