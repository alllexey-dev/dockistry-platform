package io.dockistry.platform.auth.infrastructure

import io.dockistry.platform.shared.config.AppConfig
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder

@Component
class EmailVerificationLinks(
    private val appConfig: AppConfig,
    private val properties: EmailVerificationProperties
) {
    fun buildVerificationLink(linkCode: String): String {
        return UriComponentsBuilder
            .fromUriString(appConfig.publicBaseUrl)
            .path(properties.linkPath)
            .pathSegment(linkCode)
            .build()
            .toUriString()
    }
}
