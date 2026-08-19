package io.dockistry.platform.services.application.ports

import java.time.Duration

interface ExecutionGate {
    fun <T> withLock(
        advisoryLockKey: Long,
        timeout: Duration,
        block: () -> T
    ): T
}

class ExecutionGateOverloadException(
    cause: Throwable
) :
    RuntimeException(
        "Couldn't acquire the execution gate under timeout due to overload",
        cause
    )

class ExecutionGateLockTimeoutException :
    RuntimeException(
        "Couldn't acquire the execution gate lock due to timeout"
    )

class ExecutionGateLockFailureException(
    cause: Throwable
) : RuntimeException(
    "Internal error encountered while trying to acquire the execution gate lock",
    cause
)
