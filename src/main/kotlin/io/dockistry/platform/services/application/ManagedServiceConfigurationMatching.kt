package io.dockistry.platform.services.application

import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceConfiguration
import io.dockistry.platform.services.domain.ManagedServiceSecretReference

fun ManagedService.matches(
    configuration: ManagedServiceConfiguration,
    existingReferences: Collection<ManagedServiceSecretReference>
): Boolean {
    return imageRepository == configuration.imageRef.repository &&
            imageTag == configuration.imageRef.tag &&
            existingReferences.toSet() == configuration.secretReferences.toSet() &&
            command == configuration.command &&
            args == configuration.args &&
            env == configuration.env &&
            endpoints == configuration.endpoints &&
            volumes == configuration.volumes &&
            restartPolicy == configuration.restartPolicy &&
            restartMaxRetries == configuration.restartMaxRetries &&
            memoryLimitBytes == configuration.memoryLimitBytes &&
            cpuLimitNanoCpus == configuration.cpuLimitNanoCpus &&
            desiredState == configuration.desiredState
}
