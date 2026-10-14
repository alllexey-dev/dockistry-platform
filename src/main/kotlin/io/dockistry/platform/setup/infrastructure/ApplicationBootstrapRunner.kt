package io.dockistry.platform.setup.infrastructure

import io.dockistry.platform.iam.application.SystemRolesBootstrapService
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class ApplicationBootstrapRunner(
    private val systemRolesBootstrapService: SystemRolesBootstrapService,
    private val initialAdminBootstrapService: InitialAdminBootstrapService
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        systemRolesBootstrapService.bootstrap()
        initialAdminBootstrapService.bootstrap()
    }
}
