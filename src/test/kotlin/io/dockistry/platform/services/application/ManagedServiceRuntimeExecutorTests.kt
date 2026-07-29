package io.dockistry.platform.services.application

import io.dockistry.platform.docker.domain.model.DockerContainerState
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.infrastructure.LocalDockerAgentClient
import io.dockistry.platform.services.application.dto.ManagedServiceRuntimeOperationContext
import io.dockistry.platform.services.application.ports.ExecutionGate
import io.dockistry.platform.services.application.ports.ExecutionGateLockTimeoutException
import io.dockistry.platform.services.domain.ManagedServiceLifecycleStatus
import io.dockistry.platform.services.domain.ManagedServiceOperationInProgressException
import io.dockistry.platform.services.infrastructure.ServiceOperationGateConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class ManagedServiceRuntimeExecutorTests {
    @Mock
    private lateinit var runtimeLifecycleService: ManagedServiceRuntimeLifecycleService

    @Mock
    private lateinit var deploymentLifecycleService: ManagedServiceDeploymentLifecycleService

    @Mock
    private lateinit var dockerAgentClient: LocalDockerAgentClient

    private lateinit var executionGate: RecordingExecutionGate
    private lateinit var executor: ManagedServiceRuntimeExecutor

    @BeforeEach
    fun setUp() {
        executionGate = RecordingExecutionGate()
        executor = ManagedServiceRuntimeExecutor(
            runtimeLifecycleService = runtimeLifecycleService,
            deploymentLifecycleService = deploymentLifecycleService,
            dockerAgentClient = dockerAgentClient,
            serviceOperationGate = ManagedServiceOperationGate(
                executionGate = executionGate,
                config = ServiceOperationGateConfig(Duration.ofSeconds(5))
            )
        )
    }

    @Test
    fun `all lifecycle operations execute under service gate`() {
        val serviceId = UUID.randomUUID()
        val projectId = UUID.randomUUID()
        val container = containerSummary()
        val start = operation(serviceId, projectId, ManagedServiceLifecycleStatus.STARTING)
        val stop = operation(serviceId, projectId, ManagedServiceLifecycleStatus.STOPPING)
        val pause = operation(serviceId, projectId, ManagedServiceLifecycleStatus.PAUSING)
        val unpause = operation(serviceId, projectId, ManagedServiceLifecycleStatus.UNPAUSING)
        Mockito.`when`(runtimeLifecycleService.beginStart(serviceId)).thenReturn(start)
        Mockito.`when`(runtimeLifecycleService.beginStop(serviceId)).thenReturn(stop)
        Mockito.`when`(runtimeLifecycleService.beginPause(serviceId)).thenReturn(pause)
        Mockito.`when`(runtimeLifecycleService.beginUnpause(serviceId)).thenReturn(unpause)
        Mockito.`when`(
            dockerAgentClient.listContainers(
                executor.containerFilter(projectId, serviceId)
            )
        ).thenAnswer {
            assertTrue(executionGate.insideLock)
            listOf(container)
        }

        executor.start(serviceId)
        executor.stop(serviceId)
        executor.pause(serviceId)
        executor.unpause(serviceId)

        assertEquals(4, executionGate.lockCalls)
        Mockito.verify(dockerAgentClient).startContainer(CONTAINER_ID)
        Mockito.verify(dockerAgentClient).stopContainer(CONTAINER_ID)
        Mockito.verify(dockerAgentClient).pauseContainer(CONTAINER_ID)
        Mockito.verify(dockerAgentClient).unpauseContainer(CONTAINER_ID)
        Mockito.verify(runtimeLifecycleService).completeStart(start)
        Mockito.verify(runtimeLifecycleService).completeStop(stop)
        Mockito.verify(runtimeLifecycleService).completePause(pause)
        Mockito.verify(runtimeLifecycleService).completeUnpause(unpause)
    }

    @Test
    fun `stop runtime failure is failed once while service gate is held`() {
        val serviceId = UUID.randomUUID()
        val operation = operation(
            serviceId,
            UUID.randomUUID(),
            ManagedServiceLifecycleStatus.STOPPING
        )
        val runtimeFailure = RuntimeException("stop failed")
        Mockito.`when`(runtimeLifecycleService.beginStop(serviceId)).thenReturn(operation)
        Mockito.`when`(
            dockerAgentClient.listContainers(
                executor.containerFilter(operation.projectId, serviceId)
            )
        ).thenReturn(listOf(containerSummary()))
        Mockito.doThrow(runtimeFailure)
            .`when`(dockerAgentClient)
            .stopContainer(CONTAINER_ID)
        Mockito.doAnswer {
            assertTrue(executionGate.insideLock)
            null
        }.`when`(runtimeLifecycleService).failStop(operation)

        val thrown = assertFailsWith<RuntimeException> {
            executor.stop(serviceId)
        }

        assertSame(runtimeFailure, thrown)
        Mockito.verify(runtimeLifecycleService, Mockito.times(1)).failStop(operation)
        Mockito.verify(runtimeLifecycleService, Mockito.never()).completeStop(operation)
        assertFalse(executionGate.insideLock)
    }

    @Test
    fun `stop cancels deployment captured by lifecycle operation`() {
        val serviceId = UUID.randomUUID()
        val deploymentId = UUID.randomUUID()
        val operation = operation(
            serviceId = serviceId,
            projectId = UUID.randomUUID(),
            status = ManagedServiceLifecycleStatus.STOPPING,
            deploymentId = deploymentId
        )
        Mockito.`when`(runtimeLifecycleService.beginStop(serviceId)).thenReturn(operation)
        Mockito.`when`(
            dockerAgentClient.listContainers(
                executor.containerFilter(operation.projectId, serviceId)
            )
        ).thenReturn(emptyList())
        Mockito.`when`(
            deploymentLifecycleService.requestDeploymentCancellation(
                deploymentId
            )
        ).thenReturn(ManagedServiceDeploymentCancellationResult.Requested)

        executor.stop(serviceId)

        Mockito.verify(deploymentLifecycleService)
            .requestDeploymentCancellation(deploymentId)
        Mockito.verify(runtimeLifecycleService).completeStop(operation)
    }

    @Test
    fun `finalization failure leaves runtime operation for reaper`() {
        val serviceId = UUID.randomUUID()
        val operation = operation(
            serviceId,
            UUID.randomUUID(),
            ManagedServiceLifecycleStatus.STARTING
        )
        val finalizationFailure = RuntimeException("complete failed")
        Mockito.`when`(runtimeLifecycleService.beginStart(serviceId)).thenReturn(operation)
        Mockito.`when`(
            dockerAgentClient.listContainers(
                executor.containerFilter(operation.projectId, serviceId)
            )
        ).thenReturn(listOf(containerSummary()))
        Mockito.doThrow(finalizationFailure)
            .`when`(runtimeLifecycleService)
            .completeStart(operation)

        val thrown = assertFailsWith<RuntimeException> {
            executor.start(serviceId)
        }

        assertSame(finalizationFailure, thrown)
        Mockito.verify(runtimeLifecycleService, Mockito.never()).failStart(operation)
        assertFalse(executionGate.insideLock)
    }

    @Test
    fun `gate acquisition failure does not begin lifecycle operation`() {
        executionGate.failure = ExecutionGateLockTimeoutException()

        assertFailsWith<ManagedServiceOperationInProgressException> {
            executor.pause(UUID.randomUUID())
        }

        Mockito.verifyNoInteractions(runtimeLifecycleService)
        Mockito.verifyNoInteractions(dockerAgentClient)
    }

    private fun operation(
        serviceId: UUID,
        projectId: UUID,
        status: ManagedServiceLifecycleStatus,
        deploymentId: UUID? = null
    ) = ManagedServiceRuntimeOperationContext(
        serviceId = serviceId,
        projectId = projectId,
        deploymentId = deploymentId,
        expectedStatus = status,
        expectedVersion = 1
    )

    private fun containerSummary() = DockerContainerSummary(
        id = CONTAINER_ID,
        name = null,
        names = emptyList(),
        image = null,
        imageId = null,
        command = null,
        createdEpochSeconds = null,
        state = DockerContainerState.RUNNING,
        status = null,
        ports = emptyList(),
        labels = emptyMap(),
        sizeRwBytes = null,
        sizeRootFsBytes = null,
        networkMode = null,
        networks = emptyMap(),
        mounts = emptyList()
    )

    private class RecordingExecutionGate : ExecutionGate {
        var insideLock = false
            private set
        var lockCalls = 0
            private set
        var failure: RuntimeException? = null

        override fun <T> withLock(
            advisoryLockKey: Long,
            timeout: Duration,
            block: () -> T
        ): T {
            failure?.let { throw it }
            if (insideLock) {
                throw AssertionError("Service operation gate was nested")
            }

            lockCalls += 1
            insideLock = true
            try {
                return block()
            } finally {
                insideLock = false
            }
        }
    }

    private companion object {
        const val CONTAINER_ID = "container-id"
    }
}
