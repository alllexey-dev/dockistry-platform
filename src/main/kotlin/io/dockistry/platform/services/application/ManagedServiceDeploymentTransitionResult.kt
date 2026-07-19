package io.dockistry.platform.services.application

enum class ManagedServiceDeploymentFinalizationResult {
    APPLIED,
    CANCELLATION_REQUESTED
}

sealed interface ManagedServiceDeploymentCancellationResult {
    data object Requested : ManagedServiceDeploymentCancellationResult

    data object NoDeploymentInProcess : ManagedServiceDeploymentCancellationResult

    data class DeploymentFinished(
        val containerId: String
    ) : ManagedServiceDeploymentCancellationResult
}
