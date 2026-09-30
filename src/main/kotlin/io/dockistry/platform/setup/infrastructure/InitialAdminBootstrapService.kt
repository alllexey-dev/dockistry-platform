package io.dockistry.platform.setup.infrastructure

import io.dockistry.platform.setup.application.InitialAdminService
import io.dockistry.platform.setup.domain.InitialAdminAlreadyExistsException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class InitialAdminBootstrapService(
    private val adminService: InitialAdminService,
    private val setupConfig: InitialAdminSetupConfig
) {

    companion object {
        val logger: Logger =
            LoggerFactory.getLogger(InitialAdminBootstrapService::class.java)
    }

    fun bootstrap() {
        if (isSetupConfigBlank()) {
            logger.info("Some of initial admin credentials are empty, exiting.")
            return
        }

        try {
            adminService.initializeAdmin(
                username = setupConfig.username,
                email = setupConfig.email,
                password = setupConfig.password
            )
        } catch (_: InitialAdminAlreadyExistsException) {
            logger.info("Initial admin already exists, exiting.")
        }
    }

    private fun isSetupConfigBlank(): Boolean {
        return (setupConfig.email.isBlank() ||
                setupConfig.username.isBlank() ||
                setupConfig.password.isBlank()
                )
    }

}
