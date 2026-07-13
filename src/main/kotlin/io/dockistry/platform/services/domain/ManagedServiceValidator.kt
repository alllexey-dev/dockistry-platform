package io.dockistry.platform.services.domain

import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType
import io.dockistry.platform.shared.domain.SlugValidator

object ManagedServiceValidator {
    const val MAX_NAME_LENGTH = 40

    private const val MIN_PORT = 1
    private const val MAX_PORT = 65_535
    private val envNameRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val volumeNameRegex = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]+$")

    internal fun validateConfiguration(
        draft: ManagedServiceConfigurationDraft
    ): ValidatedManagedServiceConfiguration {
        return ValidatedManagedServiceConfiguration(
            imageRef = ManagedServiceImageRefNormalizer.normalize(draft.imageRef),
            command = ensureNotBlank(draft.command, "command"),
            args = ensureNotBlank(draft.args, "args"),
            env = validateEnv(draft.env),
            endpoints = validateEndpoints(draft.endpoints),
            volumes = validateVolumes(draft.volumes),
            restartPolicy = draft.restartPolicy,
            restartMaxRetries = validateRestartPolicy(
                draft.restartPolicy,
                draft.restartMaxRetries
            ),
            memoryLimitBytes = validatePositive(
                draft.memoryLimitBytes,
                "memoryLimitBytes"
            ),
            cpuLimitNanoCpus = validatePositive(
                draft.cpuLimitNanoCpus,
                "cpuLimitNanoCpus"
            ),
            desiredState = draft.desiredState
        )
    }

    fun validateName(name: String): String {
        return SlugValidator.validate(name, MAX_NAME_LENGTH) {
            InvalidManagedServiceNameException(
                "Managed service name '$it' must be a slug up to $MAX_NAME_LENGTH characters"
            )
        }
    }

    fun ensureNotBlank(values: List<String>?, fieldName: String): List<String>? {
        if (values == null) {
            return null
        }

        if (values.any { it.isBlank() }) {
            throw InvalidManagedServiceConfigException(
                "Managed service $fieldName must not contain blank values"
            )
        }

        return values
    }

    fun validateEnv(env: Map<String, String>): Map<String, String> {
        val invalidKey = env.keys.firstOrNull { !it.matches(envNameRegex) }
        if (invalidKey != null) {
            throw InvalidManagedServiceConfigException(
                "Managed service env key '$invalidKey' is invalid"
            )
        }

        return env
    }

    fun validateEndpoints(endpoints: List<ServiceEndpoint>): List<ServiceEndpoint> {
        endpoints.forEach { endpoint ->
            validatePort(endpoint.port, "endpoint port")

            when (endpoint.mode) {
                ServiceEndpointMode.DIRECT -> if (endpoint.externalPort == null) {
                    throw InvalidManagedServiceConfigException(
                        "DIRECT service endpoint requires externalPort"
                    )
                }

                ServiceEndpointMode.EDGE -> if (endpoint.externalPort != null) {
                    throw InvalidManagedServiceConfigException(
                        "EDGE service endpoint must not define externalPort"
                    )
                }
            }

            endpoint.externalPort?.let { validatePort(it, "endpoint externalPort") }
        }

        val uniqueEndpointTargets = endpoints.distinctBy {
            Triple(it.port, it.protocol, it.mode)
        }
        if (uniqueEndpointTargets.size != endpoints.size) {
            throw InvalidManagedServiceConfigException(
                "Managed service endpoints must not contain duplicates"
            )
        }

        val directExternalPorts = endpoints
            .filter { it.mode == ServiceEndpointMode.DIRECT }
            .map {
                (it.externalPort ?: throw InvalidManagedServiceConfigException(
                    "DIRECT service endpoint requires externalPort"
                )) to it.protocol
            }
        if (directExternalPorts.distinct().size != directExternalPorts.size) {
            throw InvalidManagedServiceConfigException(
                "Managed service DIRECT endpoints must not reuse externalPort and protocol"
            )
        }

        return endpoints
    }

    fun validateVolumes(volumes: List<ServiceVolumeMount>): List<ServiceVolumeMount> {
        volumes.forEach { volume ->
            if (!volume.name.matches(volumeNameRegex)) {
                throw InvalidManagedServiceConfigException(
                    "Managed service volume name '${volume.name}' is invalid"
                )
            }

            if (!volume.mountPath.startsWith("/")) {
                throw InvalidManagedServiceConfigException(
                    "Managed service volume mountPath '${volume.mountPath}' is invalid"
                )
            }
        }

        if (volumes.distinctBy { it.name }.size != volumes.size) {
            throw InvalidManagedServiceConfigException(
                "Managed service volumes must not reuse names"
            )
        }
        if (volumes.distinctBy { it.mountPath }.size != volumes.size) {
            throw InvalidManagedServiceConfigException(
                "Managed service volumes must not reuse mount paths"
            )
        }

        return volumes
    }

    fun validateRestartPolicy(
        restartPolicy: DockerRestartPolicyType,
        restartMaxRetries: Int?
    ): Int? {
        if (restartPolicy != DockerRestartPolicyType.ON_FAILURE && restartMaxRetries != null) {
            throw InvalidManagedServiceConfigException(
                "Managed service restartMaxRetries can be set only with ON_FAILURE policy"
            )
        }

        if (restartMaxRetries != null && restartMaxRetries < 0) {
            throw InvalidManagedServiceConfigException(
                "Managed service restartMaxRetries must not be negative"
            )
        }

        return restartMaxRetries
    }

    fun validatePositive(value: Long?, fieldName: String): Long? {
        if (value != null && value <= 0) {
            throw InvalidManagedServiceConfigException(
                "Managed service $fieldName must be positive"
            )
        }

        return value
    }

    fun validateExpectedVersion(expectedVersion: Long): Long {
        if (expectedVersion < 0) {
            throw InvalidManagedServiceConfigException(
                "Managed service expectedVersion must not be negative"
            )
        }

        return expectedVersion
    }

    private fun validatePort(port: Int, fieldName: String) {
        if (port !in MIN_PORT..MAX_PORT) {
            throw InvalidManagedServiceConfigException(
                "Managed service $fieldName must be between $MIN_PORT and $MAX_PORT"
            )
        }
    }
}

internal data class ValidatedManagedServiceConfiguration(
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
)
