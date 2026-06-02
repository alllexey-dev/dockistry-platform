package io.dockistry.platform.dto

data class TokenPair(
    val refreshToken: String,
    val accessToken: String
)