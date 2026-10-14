package io.dockistry.platform.secrets.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.secrets.encryption")
class SecretEncryptionProperties {
    /** Base64-encoded 256-bit AES key. */
    var key: String = ""
}
