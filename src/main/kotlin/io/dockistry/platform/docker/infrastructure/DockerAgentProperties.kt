package io.dockistry.platform.docker.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.docker-agent")
data class DockerAgentProperties(
    val imagePullTimeout: Long
)
