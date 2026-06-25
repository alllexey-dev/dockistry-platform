package io.dockistry.platform.setup.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.setup.initial-admin")
data class InitialAdminSetupConfig(
    val username: String,
    val email: String,
    val password: String
)
