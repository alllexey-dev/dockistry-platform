package io.dockistry.platform.auth.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.auth.email-verification")
data class EmailVerificationProperties(
    val linkPath: String,
    val numberPath: String,

    val hashSecretBase64: String,

    val linkCodeExpiration: Long,
    val numberCodeExpiration: Long,

    val resendCooldown: Long,
    val rateLimit: Int,
    val rateLimitWindow: Long,
    val rateLimitThrottleInterval: Long
)
