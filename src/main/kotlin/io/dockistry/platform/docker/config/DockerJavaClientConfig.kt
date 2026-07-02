package io.dockistry.platform.docker.config

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class DockerJavaClientConfig {
    @Bean(destroyMethod = "close")
    fun dockerClient(): DockerClient {
        val clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build()
        val httpClient = ZerodepDockerHttpClient.Builder()
            .dockerHost(clientConfig.dockerHost)
            .sslConfig(clientConfig.sslConfig)
            .maxConnections(MAX_CONNECTIONS)
            .connectionTimeout(Duration.ofSeconds(CONNECTION_TIMEOUT_SECONDS))
            .responseTimeout(Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
            .build()

        return DockerClientImpl.getInstance(clientConfig, httpClient)
    }

    private companion object {
        const val MAX_CONNECTIONS = 20
        const val CONNECTION_TIMEOUT_SECONDS = 5L
        const val RESPONSE_TIMEOUT_SECONDS = 30L
    }
}
