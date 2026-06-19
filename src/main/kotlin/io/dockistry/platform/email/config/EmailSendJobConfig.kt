package io.dockistry.platform.email.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.email-send-job")
data class EmailSendJobConfig(
    val retryInterval: Long,
    val workerJobsLimit: Int,
    val workerLockingExpiration: Long
)