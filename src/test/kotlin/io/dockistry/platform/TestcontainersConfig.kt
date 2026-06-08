package io.dockistry.platform

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer {
        return PostgreSQLContainer("postgres:18.4-alpine")
    }
}
