package io.dockistry.platform

import io.dockistry.platform.testsupport.InMemoryRawEmailSender
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration(proxyBeanMethods = false)
class TestEmailSenderConfig {
    @Bean
    @Primary
    fun inMemoryRawEmailSender(): InMemoryRawEmailSender {
        return InMemoryRawEmailSender()
    }
}
