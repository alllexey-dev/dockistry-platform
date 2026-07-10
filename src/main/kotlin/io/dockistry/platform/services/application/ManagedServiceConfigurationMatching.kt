package io.dockistry.platform.services.application

import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceConfiguration

fun ManagedService.matches(configuration: ManagedServiceConfiguration): Boolean {
    return imageRepository == configuration.imageRef.repository &&
        imageTag == configuration.imageRef.tag &&
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
