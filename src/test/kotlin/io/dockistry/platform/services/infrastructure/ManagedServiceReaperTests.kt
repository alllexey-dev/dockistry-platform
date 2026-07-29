package io.dockistry.platform.services.infrastructure

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.services.application.ManagedServiceDeletionExecutor
import io.dockistry.platform.services.application.ManagedServiceDeploymentCleanupService
import io.dockistry.platform.services.application.ManagedServiceDeploymentLifecycleService
import io.dockistry.platform.services.application.ManagedServiceDeploymentOperationGate
import io.dockistry.platform.services.application.ManagedServiceOperationGate
import io.dockistry.platform.services.application.ports.ExecutionGate
import io.dockistry.platform.services.application.ports.ExecutionGateLockTimeoutException
import io.dockistry.platform.services.application.ports.ExecutionGateOverloadException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class ManagedServiceReaperTests {
    @Mock
    private lateinit var deploymentRepository: ManagedServiceDeploymentRepository

    @Mock
    private lateinit var deploymentLifecycleService: ManagedServiceDeploymentLifecycleService

    @Mock
    private lateinit var deploymentCleanupService: ManagedServiceDeploymentCleanupService

    @Mock
    private lateinit var dockerAgentClient: DockerAgentClient

    @Mock
    private lateinit var serviceRepository: ManagedServiceRepository

    @Mock
    private lateinit var deletionExecutor: ManagedServiceDeletionExecutor

    @Mock
    private lateinit var deploymentOperationGate: ManagedServiceDeploymentOperationGate

    private lateinit var executionGate: RecordingExecutionGate
    private lateinit var reaper: ManagedServiceReaper

    @BeforeEach
    fun setUp() {
        executionGate = RecordingExecutionGate()
        val properties = ManagedServiceReaperProperties(
            deploymentTimeout = Duration.ofMinutes(10),
            deploymentBatchLimit = 100,
            runtimeOperationTimeout = RUNTIME_TIMEOUT,
            runtimeOperationBatchLimit = RUNTIME_BATCH_LIMIT,
            deletingServiceTimeout = Duration.ofMinutes(1),
            deletingServiceBatchLimit = 100,
            serviceOperationGateTimeout = REAPER_GATE_TIMEOUT
        )
        Mockito.`when`(
            deploymentRepository.findStuckDeploymentsWithProjectId(
                NOW.minus(properties.deploymentTimeout),
                properties.deploymentBatchLimit
            )
        ).thenReturn(emptyList())
        Mockito.`when`(
            serviceRepository.findStuckDeletingServices(
                NOW.minus(properties.deletingServiceTimeout),
                properties.deletingServiceBatchLimit
            )
        ).thenReturn(emptyList())

        reaper = ManagedServiceReaper(
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            deploymentRepository = deploymentRepository,
            deploymentLifecycleService = deploymentLifecycleService,
            deploymentCleanupService = deploymentCleanupService,
            dockerAgentClient = dockerAgentClient,
            serviceRepository = serviceRepository,
            deletionExecutor = deletionExecutor,
            properties = properties,
            deploymentOperationGate = deploymentOperationGate,
            serviceOperationGate = ManagedServiceOperationGate(
                executionGate = executionGate,
                config = ServiceOperationGateConfig(Duration.ofSeconds(5))
            )
        )
    }

    @Test
    fun `stuck runtime operation CAS runs while service gate is held`() {
        val candidate = StuckRuntimeOperation(UUID.randomUUID(), 7)
        Mockito.`when`(
            serviceRepository.findStuckRuntimeOperations(
                NOW.minus(RUNTIME_TIMEOUT),
                RUNTIME_BATCH_LIMIT
            )
        ).thenReturn(listOf(candidate))
        Mockito.`when`(
            serviceRepository.setRuntimeOperationToActiveIfVersion(
                candidate.id,
                candidate.version,
                NOW
            )
        ).thenAnswer {
            assertTrue(executionGate.insideLock)
            1
        }

        reaper.execute()

        assertEquals(1, executionGate.lockCalls)
        assertEquals(REAPER_GATE_TIMEOUT, executionGate.lastTimeout)
        Mockito.verify(serviceRepository).setRuntimeOperationToActiveIfVersion(
            candidate.id,
            candidate.version,
            NOW
        )
    }

    @Test
    fun `service gate failure does not prevent processing later stuck operations`() {
        val first = StuckRuntimeOperation(UUID.randomUUID(), 3)
        val second = StuckRuntimeOperation(UUID.randomUUID(), 4)
        executionGate.failOnCall = 1
        Mockito.`when`(
            serviceRepository.findStuckRuntimeOperations(
                NOW.minus(RUNTIME_TIMEOUT),
                RUNTIME_BATCH_LIMIT
            )
        ).thenReturn(listOf(first, second))

        reaper.execute()

        assertEquals(2, executionGate.lockCalls)
        Mockito.verify(serviceRepository, Mockito.never())
            .setRuntimeOperationToActiveIfVersion(
                first.id, first.version, NOW
            )
        Mockito.verify(serviceRepository)
            .setRuntimeOperationToActiveIfVersion(
                second.id, second.version, NOW
            )
    }

    @Test
    fun `execution gate capacity exhaustion stops runtime operation batch`() {
        val first = StuckRuntimeOperation(UUID.randomUUID(), 3)
        val second = StuckRuntimeOperation(UUID.randomUUID(), 4)
        executionGate.failOnCall = 1
        executionGate.failure =
            ExecutionGateOverloadException(IllegalStateException("test exhaustion"))
        Mockito.`when`(
            serviceRepository.findStuckRuntimeOperations(
                NOW.minus(RUNTIME_TIMEOUT),
                RUNTIME_BATCH_LIMIT
            )
        ).thenReturn(listOf(first, second))

        reaper.execute()

        assertEquals(1, executionGate.lockCalls)
        Mockito.verify(serviceRepository, Mockito.never())
            .setRuntimeOperationToActiveIfVersion(
                first.id, first.version, NOW
            )
        Mockito.verify(serviceRepository, Mockito.never())
            .setRuntimeOperationToActiveIfVersion(
                second.id, second.version, NOW
            )
    }

    private data class StuckRuntimeOperation(
        override val id: UUID,
        override val version: Long
    ) : StuckManagedServiceRuntimeOperation

    private class RecordingExecutionGate : ExecutionGate {
        var insideLock = false
            private set
        var lockCalls = 0
            private set
        var failOnCall: Int? = null
        var failure: RuntimeException = ExecutionGateLockTimeoutException()
        var lastTimeout: Duration? = null
            private set

        override fun <T> withLock(
            advisoryLockKey: Long,
            timeout: Duration,
            block: () -> T
        ): T {
            lockCalls += 1
            lastTimeout = timeout
            if (failOnCall == lockCalls) {
                throw failure
            }
            if (insideLock) {
                throw AssertionError("Service operation gate was nested")
            }

            insideLock = true
            try {
                return block()
            } finally {
                insideLock = false
            }
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-01-01T00:10:00Z")
        val RUNTIME_TIMEOUT: Duration = Duration.ofMinutes(1)
        val REAPER_GATE_TIMEOUT: Duration = Duration.ofMillis(500)
        const val RUNTIME_BATCH_LIMIT = 100L
    }
}
