package io.dockistry.platform.services.application

import io.dockistry.platform.services.application.ports.ExecutionGate
import io.dockistry.platform.services.application.ports.ExecutionGateLockFailureException
import io.dockistry.platform.services.application.ports.ExecutionGateLockTimeoutException
import io.dockistry.platform.services.application.ports.ExecutionGateOverloadException
import io.dockistry.platform.services.domain.ManagedServiceExecutionCapacityExhaustedException
import io.dockistry.platform.services.domain.ManagedServiceOperationConcurrencyCheckFailureException
import io.dockistry.platform.services.domain.ManagedServiceOperationInProgressException
import io.dockistry.platform.services.infrastructure.ServiceOperationGateConfig
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ManagedServiceOperationGateTests {
    @Test
    fun `default lock delegates to execution gate`() {
        val serviceId = UUID.randomUUID()
        val defaultTimeout = Duration.ofSeconds(5)
        val executionGate = StubExecutionGate()
        val operationGate = operationGate(
            executionGate = executionGate,
            defaultTimeout = defaultTimeout
        )

        val result = operationGate.withLock(serviceId) {
            "result"
        }

        assertEquals("result", result)
        assertEquals(
            expectedAdvisoryLockKey(serviceId),
            executionGate.lastAdvisoryLockKey
        )
        assertEquals(defaultTimeout, executionGate.lastTimeout)
    }

    @Test
    fun `explicit timeout delegates to execution gate`() {
        val serviceId = UUID.randomUUID()
        val timeout = Duration.ofSeconds(3)
        val executionGate = StubExecutionGate()
        val operationGate = operationGate(executionGate)

        operationGate.withLock(serviceId, timeout) {}

        assertEquals(
            expectedAdvisoryLockKey(serviceId),
            executionGate.lastAdvisoryLockKey
        )
        assertEquals(timeout, executionGate.lastTimeout)
    }

    @Test
    fun `lock timeout is translated to operation in progress`() {
        val gateFailure = ExecutionGateLockTimeoutException()
        val operationGate = operationGate(StubExecutionGate(gateFailure))

        val exception = assertFailsWith<ManagedServiceOperationInProgressException> {
            operationGate.withLock(UUID.randomUUID()) {}
        }

        assertSame(gateFailure, exception.cause)
    }

    @Test
    fun `lock failure is translated to concurrency check failure`() {
        val gateFailure = ExecutionGateLockFailureException(
            IllegalStateException("test failure")
        )
        val operationGate = operationGate(StubExecutionGate(gateFailure))

        val exception =
            assertFailsWith<ManagedServiceOperationConcurrencyCheckFailureException> {
                operationGate.withLock(UUID.randomUUID()) {}
            }

        assertSame(gateFailure, exception.cause)
    }

    @Test
    fun `execution gate overload is translated to service capacity exhaustion`() {
        val gateFailure = ExecutionGateOverloadException(
            IllegalStateException("test overload")
        )
        val operationGate = operationGate(StubExecutionGate(gateFailure))

        val exception =
            assertFailsWith<ManagedServiceExecutionCapacityExhaustedException> {
                operationGate.withLock(UUID.randomUUID()) {}
            }

        assertSame(gateFailure, exception.cause)
    }

    private fun operationGate(
        executionGate: ExecutionGate,
        defaultTimeout: Duration = Duration.ofSeconds(5)
    ): ManagedServiceOperationGate {
        return ManagedServiceOperationGate(
            executionGate = executionGate,
            config = ServiceOperationGateConfig(defaultTimeout)
        )
    }

    private fun expectedAdvisoryLockKey(serviceId: UUID): Long {
        val bytes = MessageDigest
            .getInstance("SHA-256")
            .digest("managed-service: $serviceId".toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(bytes).long
    }

    private class StubExecutionGate(
        private val failure: RuntimeException? = null
    ) : ExecutionGate {
        var lastAdvisoryLockKey: Long? = null
            private set
        var lastTimeout: Duration? = null
            private set

        override fun <T> withLock(
            advisoryLockKey: Long,
            timeout: Duration,
            block: () -> T
        ): T {
            lastAdvisoryLockKey = advisoryLockKey
            lastTimeout = timeout
            failure?.let { throw it }
            return block()
        }
    }
}
