package io.dockistry.platform.services.application.ports

import java.time.Duration
import java.util.UUID

interface DeploymentExecutionGate {
    fun <T> withLock(deploymentId: UUID, block: () -> T): T
    fun <T> withLock(deploymentId: UUID, timeout: Duration, block: () -> T): T
}

class DeploymentExecutionGateOverloadException(
    cause: Throwable
) :
    RuntimeException(
        "Couldn't acquire the execution gate under timeout due to overload",
        cause
    )

class DeploymentExecutionGateLockTimeoutException :
    RuntimeException(
        "Couldn't acquire deployment execution gate lock required to continue " +
                "the deployment operation due to timeout"
    )

class DeploymentExecutionGateLockFailureException(
    cause: Throwable
) : RuntimeException(
    "Internal error encountered while trying to acquire deployment execution " +
            "gate lock required to continue the deployment operation",
    cause
)
