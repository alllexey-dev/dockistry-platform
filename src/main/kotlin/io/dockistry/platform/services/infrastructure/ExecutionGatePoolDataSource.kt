package io.dockistry.platform.services.infrastructure

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ExecutionGatePoolDataSource {
    @Bean(name = ["executionGateHikariConfig"])
    @ConfigurationProperties(prefix = "app.datasource.execution-gate.hikari")
    fun hikariConfig(): HikariConfig {
        return HikariConfig()
    }

    @Bean(
        name = ["executionGateDataSource"],
        defaultCandidate = false
    )
    fun hikariDataSource(
        jdbcConnectionDetails: JdbcConnectionDetails,

        @Qualifier("executionGateHikariConfig")
        hikariConfig: HikariConfig
    ): HikariDataSource {
        hikariConfig.jdbcUrl = jdbcConnectionDetails.jdbcUrl
        hikariConfig.username = jdbcConnectionDetails.username
        hikariConfig.password = jdbcConnectionDetails.password

        return HikariDataSource(hikariConfig)
    }
}
