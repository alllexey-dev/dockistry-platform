package io.dockistry.platform.auth.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "jwt")
@Configuration
@ConfigurationPropertiesScan
class JwtConfig {
    var secret: String = ""
    var refreshExpiration: Long = 0
    var accessExpiration: Long = 0
}