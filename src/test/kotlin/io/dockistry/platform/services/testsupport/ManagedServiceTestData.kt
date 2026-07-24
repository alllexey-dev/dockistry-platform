package io.dockistry.platform.services.testsupport

import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceConfiguration
import io.dockistry.platform.services.domain.ManagedServiceConfigurationDraft
import io.dockistry.platform.services.domain.ManagedServiceDeployment
import io.dockistry.platform.services.domain.ManagedServiceDesiredState
import io.dockistry.platform.services.domain.ServiceEndpoint
import io.dockistry.platform.services.domain.ServiceVolumeMount
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID

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

class ManagedServiceFixtures(
    private val entityManager: EntityManager
) {
    fun service(
        project: Project,
        name: String = "service-${unique()}",
        draft: ManagedServiceConfigurationDraft = managedServiceDraft(),
        createdAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt: Instant = createdAt
    ): ManagedService {
        return managedService(
            project = project,
            name = name,
            draft = draft,
            createdAt = createdAt,
            updatedAt = updatedAt
        ).also(entityManager::persist)
    }

    fun deployment(
        service: ManagedService,
        draft: ManagedServiceConfigurationDraft,
        createdAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt: Instant = createdAt
    ): ManagedServiceDeployment {
        return ManagedServiceDeployment(
            service = service,
            configSnapshot = ManagedServiceConfiguration.validated(draft),
            serviceVersion = service.version,
            createdAt = createdAt,
            updatedAt = updatedAt
        ).also(entityManager::persist)
    }

    private fun unique(): String =
        UUID.randomUUID().toString().replace("-", "").take(12)
}
