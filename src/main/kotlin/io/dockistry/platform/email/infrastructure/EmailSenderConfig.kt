package io.dockistry.platform.email.infrastructure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class EmailSenderConfig {
    @Bean
    fun emailSenderService() = DummyRawEmailSender()
}
