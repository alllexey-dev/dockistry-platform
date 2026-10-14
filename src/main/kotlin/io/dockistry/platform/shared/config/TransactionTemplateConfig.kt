package io.dockistry.platform.shared.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Configuration
class TransactionTemplateConfig {
    @Bean
    fun requiresNewTransactionTemplate(
        txManager: PlatformTransactionManager
    ): TransactionTemplate = TransactionTemplate(txManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
}
