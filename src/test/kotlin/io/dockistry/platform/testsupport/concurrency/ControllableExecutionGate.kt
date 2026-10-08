package io.dockistry.platform.testsupport.concurrency

import io.dockistry.platform.services.application.ports.ExecutionGate
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_EXECUTION_GATE_LOCK
import java.time.Duration
import java.util.ArrayDeque

class ControllableExecutionGate(
    private val delegate: ExecutionGate,
    private val concurrency: ConcurrencyTestSupport
) : ExecutionGate {
    private val failures =
        ThreadLocal<ArrayDeque<() -> RuntimeException>>()

    fun <T> failNext(
        count: Int,
        failureFactory: () -> RuntimeException,
        block: () -> T
    ): T {
        check(failures.get() == null) {
            "Execution gate failures are already configured for this thread"
        }

        failures.set(
            ArrayDeque(
                List(count) { failureFactory }
            )
        )
        return try {
            block()
        } finally {
            failures.remove()
        }
    }

    override fun <T> withLock(
        advisoryLockKey: Long,
        timeout: Duration,
        block: () -> T
    ): T {
        concurrency.checkpoint(BEFORE_EXECUTION_GATE_LOCK, advisoryLockKey)
        throwNextFailureIfConfigured()
        return delegate.withLock(advisoryLockKey, timeout, block)
    }

    private fun throwNextFailureIfConfigured() {
        val configuredFailures = failures.get() ?: return
        if (configuredFailures.isNotEmpty()) {
            throw configuredFailures.removeFirst().invoke()
        }
    }
}
