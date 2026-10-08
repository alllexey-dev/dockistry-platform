package io.dockistry.platform.testsupport.concurrency

import com.zaxxer.hikari.HikariDataSource
import io.dockistry.platform.services.infrastructure.PostgresExecutionGate
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary

@TestConfiguration(proxyBeanMethods = false)
@Import(ConcurrencyTestConfiguration::class)
class DeploymentConcurrencyTestConfiguration {
    @Bean
    fun executionGateTestSupport(
        @Qualifier("executionGateDataSource") dataSource: HikariDataSource
    ) = ExecutionGateTestSupport(dataSource)

    @Bean
    @Primary
    fun fakeDockerAgentClient(concurrency: ConcurrencyTestSupport) =
        FakeDockerAgentClient(concurrency)

    @Bean
    @Primary
    fun controllableExecutionGate(
        postgresExecutionGate: PostgresExecutionGate,
        concurrency: ConcurrencyTestSupport
    ) = ControllableExecutionGate(postgresExecutionGate, concurrency)
}
