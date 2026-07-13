package io.dockistry.platform.services.domain

import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType

class ManagedServiceConfiguration private constructor(
    val imageRef: ManagedServiceImageRef,
    val command: List<String>?,
    val args: List<String>?,
    val env: Map<String, String>,
    val endpoints: List<ServiceEndpoint>,
    val volumes: List<ServiceVolumeMount>,
    val restartPolicy: DockerRestartPolicyType,
    val restartMaxRetries: Int?,
    val memoryLimitBytes: Long?,
    val cpuLimitNanoCpus: Long?,
    val desiredState: ManagedServiceDesiredState
) {
    companion object {
        fun validated(draft: ManagedServiceConfigurationDraft): ManagedServiceConfiguration {
            val validated = ManagedServiceValidator.validateConfiguration(draft)

            return ManagedServiceConfiguration(
                imageRef = validated.imageRef,
                command = validated.command?.toList(),
                args = validated.args?.toList(),
                env = validated.env.toMap(),
                endpoints = validated.endpoints.toList(),
                volumes = validated.volumes.toList(),
                restartPolicy = validated.restartPolicy,
                restartMaxRetries = validated.restartMaxRetries,
                memoryLimitBytes = validated.memoryLimitBytes,
                cpuLimitNanoCpus = validated.cpuLimitNanoCpus,
                desiredState = validated.desiredState
            )
        }
    }
}
