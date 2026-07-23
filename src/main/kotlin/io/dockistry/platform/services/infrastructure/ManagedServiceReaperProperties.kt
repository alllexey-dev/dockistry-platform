package io.dockistry.platform.services.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.managed-services.reaper")
data class ManagedServiceReaperProperties(
    val deploymentTimeout: Duration,
    val deploymentBatchLimit: Long,
    val runtimeOperationTimeout: Duration,
    val runtimeOperationBatchLimit: Long,
    val deletingServiceTimeout: Duration,
    val deletingServiceBatchLimit: Long
)
