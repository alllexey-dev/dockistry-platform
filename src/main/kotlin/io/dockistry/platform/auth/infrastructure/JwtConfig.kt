package io.dockistry.platform.auth.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "jwt")
class JwtConfig {
    var secret: String = ""
    var refreshExpiration: Long = 0
    var accessExpiration: Long = 0
}