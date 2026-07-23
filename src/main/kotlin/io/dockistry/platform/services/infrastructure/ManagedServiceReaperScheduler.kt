package io.dockistry.platform.services.infrastructure

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ManagedServiceReaperScheduler(
    private val managedServiceReaper: ManagedServiceReaper
) {
    companion object {
        private val logger: Logger =
            LoggerFactory.getLogger(ManagedServiceReaperScheduler::class.java)
    }

    @Scheduled(fixedDelayString = $$"${app.managed-services.reaper.poll-interval}")
    fun processManagedServices() {
        runCatching {
            managedServiceReaper.execute()
        }.onFailure {
            logger.error("Failed to complete managed service reaper run: {}", it.message, it)
        }
    }
}
