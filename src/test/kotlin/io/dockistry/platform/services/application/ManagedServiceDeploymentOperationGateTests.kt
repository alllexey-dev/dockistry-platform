package io.dockistry.platform.services.application

import io.dockistry.platform.services.application.ports.ExecutionGate
import io.dockistry.platform.services.application.ports.ExecutionGateLockFailureException
import io.dockistry.platform.services.application.ports.ExecutionGateLockTimeoutException
import io.dockistry.platform.services.domain.DeploymentOperationConcurrencyCheckFailureException
import io.dockistry.platform.services.domain.DeploymentOperationInProgressException
import io.dockistry.platform.services.infrastructure.DeploymentOperationGateConfig
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ManagedServiceDeploymentOperationGateTests {
    @Test
    fun `default lock delegates to execution gate`() {
        val deploymentId = UUID.randomUUID()
        val defaultTimeout = Duration.ofSeconds(5)
        val executionGate = StubExecutionGate()
        val operationGate = operationGate(
            executionGate = executionGate,
            defaultTimeout = defaultTimeout
        )

        val result = operationGate.withLock(deploymentId) {
            "result"
        }

        assertEquals("result", result)
        assertEquals(
            expectedAdvisoryLockKey(deploymentId),
            executionGate.lastAdvisoryLockKey
        )
        assertEquals(defaultTimeout, executionGate.lastTimeout)
    }

    @Test
    fun `explicit timeout delegates to execution gate`() {
        val deploymentId = UUID.randomUUID()
        val timeout = Duration.ofSeconds(3)
        val executionGate = StubExecutionGate()
        val operationGate = operationGate(executionGate)

        operationGate.withLock(deploymentId, timeout) {}

        assertEquals(
            expectedAdvisoryLockKey(deploymentId),
            executionGate.lastAdvisoryLockKey
        )
        assertEquals(timeout, executionGate.lastTimeout)
    }

    @Test
    fun `lock timeout is translated to operation in progress`() {
        val gateFailure = ExecutionGateLockTimeoutException()
        val operationGate = operationGate(StubExecutionGate(gateFailure))

        val exception = assertFailsWith<DeploymentOperationInProgressException> {
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
            assertFailsWith<DeploymentOperationConcurrencyCheckFailureException> {
                operationGate.withLock(UUID.randomUUID()) {}
            }

        assertSame(gateFailure, exception.cause)
    }

    private fun operationGate(
        executionGate: ExecutionGate,
        defaultTimeout: Duration = Duration.ofSeconds(5)
    ): ManagedServiceDeploymentOperationGate {
        return ManagedServiceDeploymentOperationGate(
            executionGate = executionGate,
            config = DeploymentOperationGateConfig(defaultTimeout)
        )
    }

    private fun expectedAdvisoryLockKey(deploymentId: UUID): Long {
        val bytes = MessageDigest
            .getInstance("SHA-256")
            .digest("managed-service-deployment: $deploymentId".toByteArray(Charsets.UTF_8))
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
