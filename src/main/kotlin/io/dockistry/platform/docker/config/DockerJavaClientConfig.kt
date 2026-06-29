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
            .maxConnections(20)
            .connectionTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(30))
            .build()

        return DockerClientImpl.getInstance(clientConfig, httpClient)
    }
}
