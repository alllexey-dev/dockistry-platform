package io.dockistry.platform.testsupport.concurrency

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

@TestConfiguration(proxyBeanMethods = false)
class ConcurrencyTestConfiguration {
    @Bean
    fun concurrencyTestSupport(
        jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager
    ) = ConcurrencyTestSupport(jdbcTemplate, transactionManager)
}
