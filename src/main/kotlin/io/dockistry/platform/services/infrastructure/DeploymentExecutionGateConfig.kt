package io.dockistry.platform.services.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.managed-services.deployment-execution-gate")
data class DeploymentExecutionGateConfig(
    val lockTimeout: Duration
)
