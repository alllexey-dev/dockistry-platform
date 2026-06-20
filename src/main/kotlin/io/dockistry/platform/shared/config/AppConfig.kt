package io.dockistry.platform.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
class AppConfig {
    lateinit var publicBaseUrl: String
}