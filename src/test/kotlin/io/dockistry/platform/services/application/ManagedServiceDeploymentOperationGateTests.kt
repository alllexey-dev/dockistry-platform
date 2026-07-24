package io.dockistry.platform.services.application

import io.dockistry.platform.services.application.ports.DeploymentExecutionGate
import io.dockistry.platform.services.application.ports.DeploymentExecutionGateLockFailureException
import io.dockistry.platform.services.application.ports.DeploymentExecutionGateLockTimeoutException
import io.dockistry.platform.services.domain.DeploymentOperationConcurrencyCheckFailureException
import io.dockistry.platform.services.domain.DeploymentOperationInProgressException
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
        val executionGate = StubDeploymentExecutionGate()
        val operationGate = ManagedServiceDeploymentOperationGate(executionGate)

        val result = operationGate.withLock(deploymentId) {
            "result"
        }

        assertEquals("result", result)
        assertEquals(deploymentId, executionGate.lastDeploymentId)
        assertEquals(null, executionGate.lastTimeout)
    }

    @Test
    fun `explicit timeout delegates to execution gate`() {
        val deploymentId = UUID.randomUUID()
        val timeout = Duration.ofSeconds(3)
        val executionGate = StubDeploymentExecutionGate()
        val operationGate = ManagedServiceDeploymentOperationGate(executionGate)

        operationGate.withLock(deploymentId, timeout) {}

        assertEquals(deploymentId, executionGate.lastDeploymentId)
        assertEquals(timeout, executionGate.lastTimeout)
    }

    @Test
    fun `lock timeout is translated to operation in progress`() {
        val gateFailure = DeploymentExecutionGateLockTimeoutException()
        val operationGate = ManagedServiceDeploymentOperationGate(
            StubDeploymentExecutionGate(gateFailure)
        )

        val exception = assertFailsWith<DeploymentOperationInProgressException> {
            operationGate.withLock(UUID.randomUUID()) {}
        }

        assertSame(gateFailure, exception.cause)
    }

    @Test
    fun `lock failure is translated to concurrency check failure`() {
        val gateFailure = DeploymentExecutionGateLockFailureException(
            IllegalStateException("test failure")
        )
        val operationGate = ManagedServiceDeploymentOperationGate(
            StubDeploymentExecutionGate(gateFailure)
        )

        val exception =
            assertFailsWith<DeploymentOperationConcurrencyCheckFailureException> {
                operationGate.withLock(UUID.randomUUID()) {}
            }

        assertSame(gateFailure, exception.cause)
    }

    private class StubDeploymentExecutionGate(
        private val failure: RuntimeException? = null
    ) : DeploymentExecutionGate {
        var lastDeploymentId: UUID? = null
            private set
        var lastTimeout: Duration? = null
            private set

        override fun <T> withLock(deploymentId: UUID, block: () -> T): T {
            lastDeploymentId = deploymentId
            failure?.let { throw it }
            return block()
        }

        override fun <T> withLock(
            deploymentId: UUID,
            timeout: Duration,
            block: () -> T
        ): T {
            lastDeploymentId = deploymentId
            lastTimeout = timeout
            failure?.let { throw it }
            return block()
        }
    }
}
