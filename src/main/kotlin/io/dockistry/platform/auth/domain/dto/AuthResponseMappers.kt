package io.dockistry.platform.auth.domain.dto

fun TokenPair.toAuthResponse(): AuthResponse {
    return AuthResponse(
        refreshToken = this.refreshToken,
        accessToken = this.accessToken,
        accessExpiresAt = this.accessExpiresAt,
        refreshExpiresAt = this.refreshExpiresAt
    )
}
