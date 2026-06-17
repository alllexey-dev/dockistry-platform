package io.dockistry.platform.auth.infrastructure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class EmailSenderConfig {
    @Bean
    fun emailSenderService() = DummyEmailSenderService()
}
