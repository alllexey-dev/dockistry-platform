package io.dockistry.platform.services.application

import io.dockistry.platform.services.application.dto.ManagedServiceCreateCommand
import io.dockistry.platform.services.application.dto.ManagedServiceUpdateCommand
import io.dockistry.platform.services.domain.ManagedServiceConfiguration
import io.dockistry.platform.services.domain.ManagedServiceValidator
import org.springframework.stereotype.Component

@Component
class ManagedServiceCommandValidator {
    fun validate(command: ManagedServiceCreateCommand): ManagedServiceConfiguration {
        ManagedServiceValidator.validateName(command.name)
        return ManagedServiceConfiguration.validated(command.configuration)
    }

    fun validate(command: ManagedServiceUpdateCommand): ManagedServiceConfiguration {
        ManagedServiceValidator.validateExpectedVersion(command.expectedVersion)
        return ManagedServiceConfiguration.validated(command.configuration)
    }
}
