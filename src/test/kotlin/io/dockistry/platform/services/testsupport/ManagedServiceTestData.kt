package io.dockistry.platform.services.testsupport

import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceConfiguration
import io.dockistry.platform.services.domain.ManagedServiceConfigurationDraft
import io.dockistry.platform.services.domain.ManagedServiceDesiredState
import io.dockistry.platform.services.domain.ServiceEndpoint
import io.dockistry.platform.services.domain.ServiceVolumeMount
import java.time.Instant

fun managedServiceDraft(
    imageRef: String = "nginx",
    command: List<String>? = null,
    args: List<String>? = null,
    env: Map<String, String> = emptyMap(),
    endpoints: List<ServiceEndpoint> = emptyList(),
    volumes: List<ServiceVolumeMount> = emptyList(),
    restartPolicy: DockerRestartPolicyType = DockerRestartPolicyType.UNLESS_STOPPED,
    restartMaxRetries: Int? = null,
    memoryLimitBytes: Long? = null,
    cpuLimitNanoCpus: Long? = null,
    desiredState: ManagedServiceDesiredState = ManagedServiceDesiredState.RUNNING
): ManagedServiceConfigurationDraft {
    return ManagedServiceConfigurationDraft(
        imageRef = imageRef,
        command = command,
        args = args,
        env = env,
        endpoints = endpoints,
        volumes = volumes,
        restartPolicy = restartPolicy,
        restartMaxRetries = restartMaxRetries,
        memoryLimitBytes = memoryLimitBytes,
        cpuLimitNanoCpus = cpuLimitNanoCpus,
        desiredState = desiredState
    )
}

fun managedService(
    project: Project,
    name: String,
    draft: ManagedServiceConfigurationDraft = managedServiceDraft(),
    createdAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    updatedAt: Instant = createdAt
): ManagedService {
    return ManagedService(
        project = project,
        name = name,
        configuration = ManagedServiceConfiguration.validated(draft),
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
