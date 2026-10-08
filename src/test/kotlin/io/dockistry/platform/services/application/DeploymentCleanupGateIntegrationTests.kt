package io.dockistry.platform.services.application

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.model.DockerContainerState
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.domain.ProjectExecutionCapacityExhaustedException
import io.dockistry.platform.projects.domain.ProjectOperationConcurrencyCheckFailureException
import io.dockistry.platform.services.application.ports.ExecutionGateLockFailureException
import io.dockistry.platform.services.application.ports.ExecutionGateLockTimeoutException
import io.dockistry.platform.services.domain.DeploymentExecutionCapacityExhaustedException
import io.dockistry.platform.services.domain.DeploymentOperationConcurrencyCheckFailureException
import io.dockistry.platform.services.domain.DeploymentOperationInProgressException
import io.dockistry.platform.services.domain.ManagedServiceDeploymentCanceled
import io.dockistry.platform.services.domain.ManagedServiceDeploymentStatus
import io.dockistry.platform.services.domain.ServiceVolumeMount
import io.dockistry.platform.services.infrastructure.ManagedServiceDeploymentRepository
import io.dockistry.platform.services.infrastructure.ManagedServiceReaper
import io.dockistry.platform.services.infrastructure.ManagedServiceReaperProperties
import io.dockistry.platform.services.infrastructure.PostgresExecutionGate
import io.dockistry.platform.services.testsupport.ManagedServiceFixtures
import io.dockistry.platform.services.testsupport.managedServiceDraft
import io.dockistry.platform.testsupport.IamProjectFixtures
import io.dockistry.platform.testsupport.MutableClock
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.AFTER_CONTAINER_CREATE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.AFTER_NETWORK_CREATE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.AFTER_VOLUME_CREATE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_CONTAINER_CREATE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_CONTAINER_START
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_EXECUTION_GATE_LOCK
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_NETWORK_CREATE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_VOLUME_CREATE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyTestSupport
import io.dockistry.platform.testsupport.concurrency.ControllableExecutionGate
import io.dockistry.platform.testsupport.concurrency.DeploymentConcurrencyTestConfiguration
import io.dockistry.platform.testsupport.concurrency.ExecutionGateTestSupport
import io.dockistry.platform.testsupport.concurrency.FakeDockerAgentClient
import io.dockistry.platform.testsupport.concurrency.SimulatedContainerStartFailure
import io.dockistry.platform.testsupport.concurrency.SimulatedDeployExecutorDeath
import io.dockistry.platform.testsupport.concurrency.holdExecutionGateConnections
import io.dockistry.platform.testsupport.concurrency.holdExecutionGateLock
import io.dockistry.platform.testsupport.concurrency.holdExecutionGateLockAt
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val TEST_TIME: Instant = Instant.parse("2026-01-01T00:00:00Z")
private val TEST_EXECUTION_GATE_TIMEOUT: Duration = Duration.ofMillis(100)

@SpringBootTest(
    properties = [
        "app.managed-services.deployment-operation-gate.lock-timeout=100ms",
        "app.datasource.execution-gate.hikari.connection-timeout=250"
    ]
)
@Testcontainers
@Import(
    TestcontainersConfig::class,
    TestClockConfig::class,
    DeploymentConcurrencyTestConfiguration::class
)
class DeploymentCleanupGateIntegrationTests @Autowired constructor(
    private val dockerAgentClient: FakeDockerAgentClient,
    private val deployExecutor: ManagedServiceDeployExecutor,
    private val cleanupService: ManagedServiceDeploymentCleanupService,
    private val deploymentLifecycleService: ManagedServiceDeploymentLifecycleService,
    private val deploymentRepository: ManagedServiceDeploymentRepository,
    private val reaper: ManagedServiceReaper,
    private val clock: MutableClock,
    private val entityManager: EntityManager,
    private val executionGateSupport: ExecutionGateTestSupport,
    private val deploymentOperationGate: ManagedServiceDeploymentOperationGate,
    private val postgresExecutionGate: PostgresExecutionGate,
    private val controllableExecutionGate: ControllableExecutionGate,
    private val concurrency: ConcurrencyTestSupport,
    private val managedServiceReaperProperties: ManagedServiceReaperProperties
) {
    @BeforeEach
    fun resetDockerAgent() {
        dockerAgentClient.reset()
        clock.set(TEST_TIME)
    }

    @Test
    fun `cleanup cannot pass container creation and executor removes late container`() {
        val context = createServiceContext()

        concurrency.run {
            val beforeCreate = pauseAt(BEFORE_CONTAINER_CREATE)
            val afterCreate = pauseAt(AFTER_CONTAINER_CREATE)
            val deployment = start("deploy service") {
                deployExecutor.deploy(
                    context.serviceId,
                    context.principal,
                    ignoreWarnings = false
                )
            }

            beforeCreate.awaitReached()
            val deploymentId = requestCancellation(context.serviceId)

            assertCleanupBlocked(context, deploymentId)
            beforeCreate.release()
            afterCreate.awaitReached()

            val createdContainer = dockerAgentClient.createdContainerCommands
                .entries
                .single { (_, command) ->
                    command.labels[DockerLabels.DEPLOYMENT_ID] ==
                            deploymentId.toString()
                }
            assertTrue(
                dockerAgentClient.activeContainerIds.contains(createdContainer.key),
                "Created deployment container wasn't observable before cleanup"
            )

            afterCreate.release()
            assertFailsWith<ManagedServiceDeploymentCanceled> {
                deployment.await()
            }

            assertFalse(
                dockerAgentClient.activeContainerIds.contains(createdContainer.key),
                "Canceled deployment container wasn't removed"
            )
            assertTrue(
                dockerAgentClient.deletedContainerIds.contains(createdContainer.key),
                "Canceled deployment container deletion wasn't observed"
            )
            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.CANCELED
            )
        }
    }

    @Test
    fun `cleanup cannot pass volume creation and executor removes late volume`() {
        val volumeName = randomResourceName("data")
        val context = createServiceContext(
            volume = ServiceVolumeMount(
                name = volumeName,
                mountPath = "/data/$volumeName"
            )
        )

        concurrency.run {
            val beforeCreate = pauseAt(BEFORE_VOLUME_CREATE)
            val afterCreate = pauseAt(AFTER_VOLUME_CREATE)
            val deployment = start("deploy service") {
                deployExecutor.deploy(
                    context.serviceId,
                    context.principal,
                    ignoreWarnings = false
                )
            }

            beforeCreate.awaitReached()
            val deploymentId = requestCancellation(context.serviceId)

            assertCleanupBlocked(context, deploymentId)
            beforeCreate.release()
            afterCreate.awaitReached()

            val createdVolume = dockerAgentClient.createdVolumes.values.single {
                it.labels[DockerLabels.DEPLOYMENT_ID] == deploymentId.toString()
            }
            assertTrue(
                dockerAgentClient.activeVolumeNames.contains(createdVolume.name),
                "Created deployment volume wasn't observable before cleanup"
            )

            afterCreate.release()
            assertFailsWith<ManagedServiceDeploymentCanceled> {
                deployment.await()
            }

            assertFalse(
                dockerAgentClient.activeVolumeNames.contains(createdVolume.name),
                "Canceled deployment volume wasn't removed"
            )
            assertTrue(
                dockerAgentClient.removedVolumeNames.contains(createdVolume.name),
                "Canceled deployment volume removal wasn't observed"
            )
            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.CANCELED
            )
        }
    }

    @Test
    fun `cleanup can pass network creation and cancellation completes afterwards`() {
        val volumeName = randomResourceName("data")
        val context = createServiceContext(
            volume = ServiceVolumeMount(
                name = volumeName,
                mountPath = "/data/$volumeName"
            )
        )

        concurrency.run {
            val beforeCreate = pauseAt(BEFORE_NETWORK_CREATE)
            val afterCreate = pauseAt(AFTER_NETWORK_CREATE)
            val deployment = start("deploy service") {
                deployExecutor.deploy(
                    context.serviceId,
                    context.principal,
                    ignoreWarnings = false
                )
            }

            beforeCreate.awaitReached()
            val deploymentId = requestCancellation(context.serviceId)

            cleanupService.cleanup(
                projectId = context.projectId,
                serviceId = context.serviceId,
                deploymentId = deploymentId
            )
            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.CANCELED
            )
            beforeCreate.release()
            afterCreate.awaitReached()

            val createdNetwork = dockerAgentClient.createdNetworks.values.single {
                it.labels[DockerLabels.PROJECT_ID] == context.projectId.toString()
            }
            assertEquals(
                createdNetwork,
                dockerAgentClient.networksByName[createdNetwork.name],
                "Created project network wasn't observable before cancellation continued"
            )

            afterCreate.release()
            assertFailsWith<ManagedServiceDeploymentCanceled> {
                deployment.await()
            }

            assertTrue(
                dockerAgentClient.createdVolumes.isEmpty(),
                "Canceled deployment created a volume after network creation"
            )
            assertTrue(
                dockerAgentClient.createdContainerCommands.isEmpty(),
                "Canceled deployment created a container after network creation"
            )
            assertEquals(
                createdNetwork,
                dockerAgentClient.networksByName[createdNetwork.name],
                "Shared project network should remain available after cancellation"
            )
            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.CANCELED
            )
        }
    }

    @Test
    fun `reaper cannot enter while deploy executor is starting container`() {
        val context = createServiceContext()

        concurrency.run {
            val beforeStart = pauseAt(BEFORE_CONTAINER_START)
            val deployment = start("deploy service") {
                deployExecutor.deploy(
                    context.serviceId,
                    context.principal,
                    ignoreWarnings = false
                )
            }

            beforeStart.awaitReached()
            val deploymentId = activeDeploymentId(context.serviceId)
            val container = deploymentContainer(context, deploymentId)
            assertEquals(DockerContainerState.CREATED, container.state)

            advanceTimeToGetDeploymentMarkedAsStuck()
            reaper.execute()

            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.IN_PROCESS
            )

            beforeStart.release()
            deployment.await()

            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.FINISHED
            )
        }
    }

    @Test
    fun `reaper cancels and cleans deployment when executor dies before container start`() {
        val context = createServiceContext()
        dockerAgentClient.executorDeathPoint =
            FakeDockerAgentClient.ExecutorDeathPoint.AFTER_CONTAINER_CREATION

        concurrency.run {
            val deployment = start("deploy service") {
                deployExecutor.deploy(
                    context.serviceId,
                    context.principal,
                    ignoreWarnings = false
                )
            }

            val executorDeath = assertIs<SimulatedDeployExecutorDeath>(deployment.failure())
            assertEquals(
                FakeDockerAgentClient.ExecutorDeathPoint.AFTER_CONTAINER_CREATION,
                executorDeath.deathPoint
            )

            val deploymentId = activeDeploymentId(context.serviceId)
            val container = deploymentContainer(context, deploymentId)
            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.IN_PROCESS
            )
            assertEquals(DockerContainerState.CREATED, container.state)

            advanceTimeToGetDeploymentMarkedAsStuck()
            reaper.execute()

            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.CANCELED
            )
            assertFalse(
                dockerAgentClient.activeContainerIds.contains(container.id),
                "Container left by dead deploy executor wasn't removed"
            )
            assertTrue(
                dockerAgentClient.deletedContainerIds.contains(container.id),
                "Container cleanup wasn't observed"
            )
        }
    }

    @Test
    fun `reaper finishes deployment when executor dies after container start`() {
        val context = createServiceContext()
        dockerAgentClient.executorDeathPoint =
            FakeDockerAgentClient.ExecutorDeathPoint.AFTER_CONTAINER_START

        concurrency.run {
            val deployment = start("deploy service") {
                deployExecutor.deploy(
                    context.serviceId,
                    context.principal,
                    ignoreWarnings = false
                )
            }

            val executorDeath = assertIs<SimulatedDeployExecutorDeath>(deployment.failure())
            assertEquals(
                FakeDockerAgentClient.ExecutorDeathPoint.AFTER_CONTAINER_START,
                executorDeath.deathPoint
            )

            val deploymentId = activeDeploymentId(context.serviceId)
            val container = deploymentContainer(context, deploymentId)
            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.IN_PROCESS
            )
            assertEquals(DockerContainerState.RUNNING, container.state)

            advanceTimeToGetDeploymentMarkedAsStuck()
            reaper.execute()

            val finishedDeployment =
                deploymentRepository.findById(deploymentId).orElseThrow()
            assertEquals(
                ManagedServiceDeploymentStatus.FINISHED,
                finishedDeployment.status
            )
            assertEquals(container.id, finishedDeployment.containerId)
            assertTrue(
                dockerAgentClient.activeContainerIds.contains(container.id),
                "Running deployment container should remain available"
            )
        }
    }

    @Test
    fun `start failure is recorded while reaper is fenced`() {
        val context = createServiceContext()
        val startFailure = SimulatedContainerStartFailure()
        dockerAgentClient.startContainerFailure = startFailure

        concurrency.run {
            val beforeStart = pauseAt(BEFORE_CONTAINER_START)
            val deployment = start("deploy service") {
                deployExecutor.deploy(
                    context.serviceId,
                    context.principal,
                    ignoreWarnings = false
                )
            }

            beforeStart.awaitReached()
            val deploymentId = activeDeploymentId(context.serviceId)

            advanceTimeToGetDeploymentMarkedAsStuck()
            reaper.execute()

            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.IN_PROCESS
            )

            beforeStart.release()
            assertSame(startFailure, deployment.failure())

            val failedDeployment =
                deploymentRepository.findById(deploymentId).orElseThrow()
            assertEquals(
                ManagedServiceDeploymentStatus.FAILED,
                failedDeployment.status
            )
            assertEquals(startFailure.message, failedDeployment.errorMessage)
            assertEquals(
                DockerContainerState.CREATED,
                deploymentContainer(context, deploymentId).state
            )
        }
    }

    @Test
    fun `execution gate rejects work when its connection pool is exhausted`() {
        val poolSize = executionGateSupport.poolCapacity
        concurrency.run(workers = poolSize) {
            holdExecutionGateConnections(
                postgresExecutionGate, poolSize, TEST_EXECUTION_GATE_TIMEOUT
            )

            assertFailsWith<DeploymentExecutionCapacityExhaustedException> {
                deploymentOperationGate.withLock(UUID.randomUUID()) {}
            }
        }
    }

    @Test
    fun `same lock waiters do not retain execution gate connections`() {
        val poolSize = executionGateSupport.poolCapacity
        assertTrue(poolSize > 1, "Execution gate pool must have more than one connection")
        val advisoryLockKey = UUID.randomUUID().mostSignificantBits

        concurrency.run(workers = poolSize) {
            holdExecutionGateLock(
                postgresExecutionGate, advisoryLockKey, TEST_EXECUTION_GATE_TIMEOUT
            )
            val beforeWaiters = executionGateSupport.capturePoolState()
            val waiters = List(poolSize - 1) {
                start("wait for occupied execution gate") {
                    postgresExecutionGate.withLock(
                        advisoryLockKey = advisoryLockKey,
                        timeout = TEST_EXECUTION_GATE_TIMEOUT
                    ) {}
                }
            }
            waiters.forEach { assertIs<ExecutionGateLockTimeoutException>(it.failure()) }
            executionGateSupport.assertOnlyHolderConnectionRemains(beforeWaiters)
        }
    }

    @Test
    fun `main database transactions work while execution gate pool is exhausted`() {
        val poolSize = executionGateSupport.poolCapacity
        concurrency.run(workers = poolSize) {
            holdExecutionGateConnections(
                postgresExecutionGate, poolSize, TEST_EXECUTION_GATE_TIMEOUT
            )

            concurrency.assertDatabaseTransactionAvailable()
        }
    }

    @Test
    fun `pool exhaustion leaves deployment in process when failure cannot be recorded`() {
        val context = createServiceContext()
        val poolSize = executionGateSupport.poolCapacity
        concurrency.run(workers = poolSize) {
            holdExecutionGateConnections(
                postgresExecutionGate, poolSize, TEST_EXECUTION_GATE_TIMEOUT
            )

            val failure = assertFailsWith<DeploymentExecutionCapacityExhaustedException> {
                deployExecutor.deploy(
                    context.serviceId,
                    context.principal,
                    ignoreWarnings = false
                )
            }

            assertIs<ProjectExecutionCapacityExhaustedException>(
                failure.suppressed.single()
            )
            assertDeploymentRemainsInProcess(context.serviceId)
        }
    }

    @Test
    fun `same deployment timeout leaves deployment in process when failure cannot be recorded`() {
        val context = createServiceContext()
        concurrency.run {
            // Skip the project gate; pause when the deployment gate key is published.
            val deploymentGate = pauseAt(BEFORE_EXECUTION_GATE_LOCK, skip = 1)
            val deployment = start("deploy service") {
                deployExecutor.deploy(
                    context.serviceId,
                    context.principal,
                    ignoreWarnings = false
                )
            }
            deploymentGate.awaitReached()
            holdExecutionGateLockAt(
                postgresExecutionGate, deploymentGate, TEST_EXECUTION_GATE_TIMEOUT
            )
            deploymentGate.release()

            val timeout = assertIs<DeploymentOperationInProgressException>(deployment.failure())
            assertIs<DeploymentOperationInProgressException>(timeout.suppressed.single())
            assertDeploymentRemainsInProcess(context.serviceId)
        }
    }

    @Test
    fun `persistent execution gate lock failure leaves deployment in process`() {
        val context = createServiceContext()

        val failure =
            assertFailsWith<DeploymentOperationConcurrencyCheckFailureException> {
                controllableExecutionGate.failNext(
                    count = 2,
                    failureFactory = ::simulatedExecutionGateLockFailure
                ) {
                    deployExecutor.deploy(
                        context.serviceId,
                        context.principal,
                        ignoreWarnings = false
                    )
                }
            }

        assertIs<ProjectOperationConcurrencyCheckFailureException>(
            failure.suppressed.single()
        )
        assertDeploymentRemainsInProcess(context.serviceId)
    }

    @Test
    fun `transient execution gate lock failure is recorded as failed`() {
        val context = createServiceContext()

        val failure =
            assertFailsWith<ProjectOperationConcurrencyCheckFailureException> {
                controllableExecutionGate.failNext(
                    count = 1,
                    failureFactory = ::simulatedExecutionGateLockFailure
                ) {
                    deployExecutor.deploy(
                        context.serviceId,
                        context.principal,
                        ignoreWarnings = false
                    )
                }
            }

        val deployment = deploymentRepository.findAll()
            .single { it.service.id == context.serviceId }
        assertEquals(ManagedServiceDeploymentStatus.FAILED, deployment.status)
        assertEquals(failure.message, deployment.errorMessage)
    }

    private fun assertDeploymentRemainsInProcess(serviceId: UUID) {
        val deployment = deploymentRepository.findActiveByServiceId(serviceId).single()
        assertEquals(ManagedServiceDeploymentStatus.IN_PROCESS, deployment.status)
        assertNull(deployment.errorMessage)
    }

    private fun simulatedExecutionGateLockFailure(): RuntimeException {
        return ExecutionGateLockFailureException(
            SQLException("Simulated execution gate lock failure")
        )
    }

    private fun createServiceContext(
        volume: ServiceVolumeMount? = null
    ): ServiceContext = concurrency.inNewTransaction {
        val suffix = randomResourceName("gate")
        val project = IamProjectFixtures(entityManager).project(
            name = "Gate Project $suffix",
            slug = "gate-project-$suffix"
        )
        val service = ManagedServiceFixtures(entityManager).service(
            project = project,
            name = "gate-service-$suffix",
            draft = managedServiceDraft(
                imageRef = "nginx:$suffix",
                volumes = listOfNotNull(volume)
            )
        )
        entityManager.flush()

        ServiceContext(
            projectId = project.id,
            serviceId = service.id,
            principal = project.ownerPrincipal
        )
    }

    private fun requestCancellation(serviceId: UUID): UUID {
        val deployment = deploymentRepository.findActiveByServiceId(serviceId).single()
        assertEquals(
            ManagedServiceDeploymentCancellationResult.Requested,
            deploymentLifecycleService.requestDeploymentCancellation(
                deployment.id
            )
        )
        return deployment.id
    }

    private fun activeDeploymentId(serviceId: UUID): UUID =
        deploymentRepository.findActiveByServiceId(serviceId).single().id

    private fun deploymentContainer(
        context: ServiceContext,
        deploymentId: UUID
    ): DockerContainerSummary {
        return dockerAgentClient.listContainers(
            DockerLabelsFilter(
                DockerLabels.forDeployment(
                    projectId = context.projectId,
                    serviceId = context.serviceId,
                    deploymentId = deploymentId
                )
            )
        ).single()
    }

    private fun advanceTimeToGetDeploymentMarkedAsStuck() {
        clock.advance(
            managedServiceReaperProperties.deploymentTimeout +
                    Duration.ofMinutes(1)
        )
    }

    private fun assertCleanupBlocked(
        context: ServiceContext,
        deploymentId: UUID
    ) {
        assertFailsWith<DeploymentOperationInProgressException> {
            cleanupService.cleanup(
                projectId = context.projectId,
                serviceId = context.serviceId,
                deploymentId = deploymentId
            )
        }
        assertDeploymentStatus(
            deploymentId,
            ManagedServiceDeploymentStatus.CANCELING
        )
    }

    private fun assertDeploymentStatus(
        deploymentId: UUID,
        expected: ManagedServiceDeploymentStatus
    ) {
        assertEquals(
            expected,
            deploymentRepository.findById(deploymentId).orElseThrow().status
        )
    }

    private fun randomResourceName(prefix: String): String =
        "$prefix-${UUID.randomUUID().toString().replace("-", "").take(8)}"

    private data class ServiceContext(
        val projectId: UUID,
        val serviceId: UUID,
        val principal: Principal
    )
}
