package io.dockistry.platform.services.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.managed-services.service-operation-gate")
data class ServiceOperationGateConfig(
    val lockTimeout: Duration
)
