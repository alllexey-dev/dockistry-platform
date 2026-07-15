package io.dockistry.platform.docker.domain.model

data class DockerContainerCreateCommand(
    val name: String,
    val image: DockerImageRef,
    val env: Map<String, String> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
    val command: List<String>? = null,
    val args: List<String>? = null,
    val networkName: String,
    val networkAliases: List<String> = emptyList(),
    val portBindings: List<DockerPortBinding> = emptyList(),
    val volumeBindings: List<DockerVolumeBinding> = emptyList(),
    val restartPolicy: DockerRestartPolicy = DockerRestartPolicy(DockerRestartPolicyType.NO),
    val memoryLimitBytes: Long? = null,
    val cpuLimitNanoCpus: Long? = null
)

data class DockerPortBinding(
    val containerPort: Int,
    val hostPort: Int,
    val protocol: DockerPortProtocol
)

enum class DockerPortProtocol {
    TCP,
    UDP
}

data class DockerVolumeBinding(
    val volumeName: String,
    val mountPath: String,
    val readOnly: Boolean = false
)

data class DockerNetworkRef(
    val id: String,
    val name: String
)
