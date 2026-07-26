package io.dockistry.platform.services.application

import com.zaxxer.hikari.HikariDataSource
import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.DockerVolumeNotFoundException
import io.dockistry.platform.docker.domain.model.DockerAgentInfo
import io.dockistry.platform.docker.domain.model.DockerContainerCreateCommand
import io.dockistry.platform.docker.domain.model.DockerContainerDetails
import io.dockistry.platform.docker.domain.model.DockerContainerLogs
import io.dockistry.platform.docker.domain.model.DockerContainerLogsRequest
import io.dockistry.platform.docker.domain.model.DockerContainerState
import io.dockistry.platform.docker.domain.model.DockerContainerStats
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerImageInfo
import io.dockistry.platform.docker.domain.model.DockerImageRef
import io.dockistry.platform.docker.domain.model.DockerImageSummary
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.docker.domain.model.DockerNetworkRef
import io.dockistry.platform.docker.domain.model.DockerVolumeRef
import io.dockistry.platform.services.application.ports.DeploymentExecutionGate
import io.dockistry.platform.services.application.ports.DeploymentExecutionGateLockFailureException
import io.dockistry.platform.services.application.ports.DeploymentExecutionGateLockTimeoutException
import io.dockistry.platform.services.domain.DeploymentExecutionCapacityExhaustedException
import io.dockistry.platform.services.domain.DeploymentOperationConcurrencyCheckFailureException
import io.dockistry.platform.services.domain.DeploymentOperationInProgressException
import io.dockistry.platform.services.domain.ManagedServiceDeploymentCanceled
import io.dockistry.platform.services.domain.ManagedServiceDeploymentStatus
import io.dockistry.platform.services.domain.ServiceVolumeMount
import io.dockistry.platform.services.infrastructure.ManagedServiceDeploymentRepository
import io.dockistry.platform.services.infrastructure.ManagedServiceReaper
import io.dockistry.platform.services.infrastructure.ManagedServiceReaperProperties
import io.dockistry.platform.services.infrastructure.PostgresDeploymentExecutionGate
import io.dockistry.platform.services.testsupport.ManagedServiceFixtures
import io.dockistry.platform.services.testsupport.managedServiceDraft
import io.dockistry.platform.testsupport.IamProjectFixtures
import io.dockistry.platform.testsupport.MutableClock
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.core.env.getRequiredProperty
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val TIMEOUT_SECONDS = 15L
private val TEST_TIME: Instant = Instant.parse("2026-01-01T00:00:00Z")

@SpringBootTest(
    properties = [
        "app.managed-services.deployment-execution-gate.lock-timeout=100ms",
        "app.datasource.execution-gate.hikari.connection-timeout=250"
    ]
)
@Testcontainers
@Import(
    TestcontainersConfig::class,
    TestClockConfig::class,
    DeploymentCleanupGateIntegrationTests.FakeDockerAgentClientConfig::class,
    DeploymentCleanupGateIntegrationTests.ControllableDeploymentExecutionGateConfig::class
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
    private val environment: Environment,
    private val deploymentOperationGate: ManagedServiceDeploymentOperationGate,
    private val postgresDeploymentExecutionGate: PostgresDeploymentExecutionGate,
    private val controllableDeploymentExecutionGate: ControllableDeploymentExecutionGate,
    @Qualifier("executionGateDataSource")
    private val executionGateDataSource: HikariDataSource,
    transactionManager: PlatformTransactionManager,
    @Autowired private val managedServiceReaperProperties: ManagedServiceReaperProperties
) {
    private val newTransactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @BeforeEach
    fun resetDockerAgent() {
        dockerAgentClient.reset()
        clock.set(TEST_TIME)
    }

    @Test
    fun `cleanup cannot pass container creation and executor removes late container`() {
        val context = createServiceContext()
        val createContainerEntered = CountDownLatch(1)
        val containerCreationAllowed = CountDownLatch(1)
        val afterCreateContainer = CountDownLatch(1)
        val afterCreateContainerContinueAllowed = CountDownLatch(1)
        dockerAgentClient.createContainerEntered = createContainerEntered
        dockerAgentClient.containerCreationAllowed = containerCreationAllowed
        dockerAgentClient.afterCreateContainer = afterCreateContainer
        dockerAgentClient.afterCreateContainerContinueAllowed =
            afterCreateContainerContinueAllowed

        val pool = Executors.newSingleThreadExecutor()
        val deployment = pool.submit<Result<Unit>> {
            runCatching {
                deployExecutor.deploy(context.serviceId)
                Unit
            }
        }

        try {
            await(createContainerEntered, "Container creation wasn't entered")
            val deploymentId = requestCancellation(context.serviceId)

            assertCleanupBlocked(context, deploymentId)
            containerCreationAllowed.countDown()
            await(afterCreateContainer, "Container wasn't created")

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

            afterCreateContainerContinueAllowed.countDown()
            assertFailsWith<ManagedServiceDeploymentCanceled> {
                deployment.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getOrThrow()
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
        } finally {
            containerCreationAllowed.countDown()
            afterCreateContainerContinueAllowed.countDown()
            terminate(pool, "Container deployment executor didn't terminate")
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
        val createVolumeEntered = CountDownLatch(1)
        val volumeCreationAllowed = CountDownLatch(1)
        val afterCreateVolume = CountDownLatch(1)
        val afterCreateVolumeContinueAllowed = CountDownLatch(1)
        dockerAgentClient.createVolumeEntered = createVolumeEntered
        dockerAgentClient.volumeCreationAllowed = volumeCreationAllowed
        dockerAgentClient.afterCreateVolume = afterCreateVolume
        dockerAgentClient.afterCreateVolumeContinueAllowed =
            afterCreateVolumeContinueAllowed

        val pool = Executors.newSingleThreadExecutor()
        val deployment = pool.submit<Result<Unit>> {
            runCatching {
                deployExecutor.deploy(context.serviceId)
                Unit
            }
        }

        try {
            await(createVolumeEntered, "Volume creation wasn't entered")
            val deploymentId = requestCancellation(context.serviceId)

            assertCleanupBlocked(context, deploymentId)
            volumeCreationAllowed.countDown()
            await(afterCreateVolume, "Volume wasn't created")

            val createdVolume = dockerAgentClient.createdVolumes.values.single {
                it.labels[DockerLabels.DEPLOYMENT_ID] == deploymentId.toString()
            }
            assertTrue(
                dockerAgentClient.activeVolumeNames.contains(createdVolume.name),
                "Created deployment volume wasn't observable before cleanup"
            )

            afterCreateVolumeContinueAllowed.countDown()
            assertFailsWith<ManagedServiceDeploymentCanceled> {
                deployment.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getOrThrow()
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
        } finally {
            volumeCreationAllowed.countDown()
            afterCreateVolumeContinueAllowed.countDown()
            terminate(pool, "Volume deployment executor didn't terminate")
        }
    }

    @Test
    fun `cleanup cannot pass network creation and cancellation completes afterwards`() {
        val context = createServiceContext()
        val createNetworkEntered = CountDownLatch(1)
        val networkCreationAllowed = CountDownLatch(1)
        val afterCreateNetwork = CountDownLatch(1)
        val afterCreateNetworkContinueAllowed = CountDownLatch(1)
        dockerAgentClient.createNetworkEntered = createNetworkEntered
        dockerAgentClient.networkCreationAllowed = networkCreationAllowed
        dockerAgentClient.afterCreateNetwork = afterCreateNetwork
        dockerAgentClient.afterCreateNetworkContinueAllowed =
            afterCreateNetworkContinueAllowed

        val pool = Executors.newSingleThreadExecutor()
        val deployment = pool.submit<Result<Unit>> {
            runCatching {
                deployExecutor.deploy(context.serviceId)
                Unit
            }
        }

        try {
            await(createNetworkEntered, "Network creation wasn't entered")
            val deploymentId = requestCancellation(context.serviceId)

            assertCleanupBlocked(context, deploymentId)
            networkCreationAllowed.countDown()
            await(afterCreateNetwork, "Network wasn't created")

            val createdNetwork = dockerAgentClient.createdNetworks.values.single {
                it.labels[DockerLabels.PROJECT_ID] == context.projectId.toString()
            }
            assertEquals(
                createdNetwork,
                dockerAgentClient.networksByName[createdNetwork.name],
                "Created project network wasn't observable before cancellation continued"
            )

            afterCreateNetworkContinueAllowed.countDown()
            assertFailsWith<ManagedServiceDeploymentCanceled> {
                deployment.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getOrThrow()
            }

            assertEquals(
                createdNetwork,
                dockerAgentClient.networksByName[createdNetwork.name],
                "Shared project network should remain available after cancellation"
            )
            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.CANCELED
            )
        } finally {
            networkCreationAllowed.countDown()
            afterCreateNetworkContinueAllowed.countDown()
            terminate(pool, "Network deployment executor didn't terminate")
        }
    }

    @Test
    fun `reaper cannot enter while deploy executor is starting container`() {
        val context = createServiceContext()
        val startContainerEntered = CountDownLatch(1)
        val startContainerContinueAllowed = CountDownLatch(1)
        dockerAgentClient.startContainerEntered = startContainerEntered
        dockerAgentClient.startContainerContinueAllowed =
            startContainerContinueAllowed

        val pool = Executors.newSingleThreadExecutor()
        val deployment = pool.submit<Unit> {
            deployExecutor.deploy(context.serviceId)
        }

        try {
            await(startContainerEntered, "Container start wasn't entered")
            val deploymentId = activeDeploymentId(context.serviceId)
            val container = deploymentContainer(context, deploymentId)
            assertEquals(DockerContainerState.CREATED, container.state)

            advanceTimeToGetDeploymentMarkedAsStuck()
            reaper.execute()

            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.IN_PROCESS
            )

            startContainerContinueAllowed.countDown()
            deployment.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.FINISHED
            )
        } finally {
            startContainerContinueAllowed.countDown()
            terminate(pool, "Deployment executor didn't terminate after container start")
        }
    }

    @Test
    fun `reaper cancels and cleans deployment when executor dies before container start`() {
        val context = createServiceContext()
        dockerAgentClient.executorDeathPoint =
            FakeDockerAgentClient.ExecutorDeathPoint.AFTER_CONTAINER_CREATION

        val pool = Executors.newSingleThreadExecutor()
        val deployment = pool.submit<Unit> {
            deployExecutor.deploy(context.serviceId)
        }

        try {
            val failure = assertFailsWith<ExecutionException> {
                deployment.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            val executorDeath = assertIs<SimulatedDeployExecutorDeath>(failure.cause)
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
        } finally {
            terminate(pool, "Dead deployment executor didn't terminate")
        }
    }

    @Test
    fun `reaper finishes deployment when executor dies after container start`() {
        val context = createServiceContext()
        dockerAgentClient.executorDeathPoint =
            FakeDockerAgentClient.ExecutorDeathPoint.AFTER_CONTAINER_START

        val pool = Executors.newSingleThreadExecutor()
        val deployment = pool.submit<Unit> {
            deployExecutor.deploy(context.serviceId)
        }

        try {
            val failure = assertFailsWith<ExecutionException> {
                deployment.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            val executorDeath = assertIs<SimulatedDeployExecutorDeath>(failure.cause)
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
        } finally {
            terminate(pool, "Dead deployment executor didn't terminate")
        }
    }

    @Test
    fun `start failure is recorded while reaper is fenced`() {
        val context = createServiceContext()
        val startContainerEntered = CountDownLatch(1)
        val startContainerContinueAllowed = CountDownLatch(1)
        val startFailure = SimulatedContainerStartFailure()
        dockerAgentClient.startContainerEntered = startContainerEntered
        dockerAgentClient.startContainerContinueAllowed =
            startContainerContinueAllowed
        dockerAgentClient.startContainerFailure = startFailure

        val pool = Executors.newSingleThreadExecutor()
        val deployment = pool.submit<Unit> {
            deployExecutor.deploy(context.serviceId)
        }

        try {
            await(startContainerEntered, "Container start wasn't entered")
            val deploymentId = activeDeploymentId(context.serviceId)

            advanceTimeToGetDeploymentMarkedAsStuck()
            reaper.execute()

            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.IN_PROCESS
            )

            startContainerContinueAllowed.countDown()
            val failure = assertFailsWith<ExecutionException> {
                deployment.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            assertSame(startFailure, failure.cause)

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
        } finally {
            startContainerContinueAllowed.countDown()
            terminate(pool, "Failing deployment executor didn't terminate")
        }
    }

    @Test
    fun `execution gate rejects work when its connection pool is exhausted`() {
        val poolSize = executionGatePoolSize()
        val holdersEntered = CountDownLatch(poolSize)
        val holdersRelease = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(poolSize)
        val holders = submitExecutionGateHolders(
            poolSize = poolSize,
            pool = pool,
            holdersEntered = holdersEntered,
            holdersRelease = holdersRelease
        )

        try {
            await(holdersEntered, "Execution gate pool wasn't exhausted")

            assertFailsWith<DeploymentExecutionCapacityExhaustedException> {
                deploymentOperationGate.withLock(UUID.randomUUID()) {}
            }
        } finally {
            holdersRelease.countDown()
            awaitSuccessful(holders)
            terminate(pool, "Execution gate pool holders didn't terminate")
        }
    }

    @Test
    fun `same lock waiters do not retain execution gate connections`() {
        val poolSize = executionGatePoolSize()
        assertTrue(
            poolSize > 1,
            "Execution gate pool must have more than one connection"
        )

        val deploymentId = UUID.randomUUID()
        val holderEntered = CountDownLatch(1)
        val holderRelease = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(poolSize)
        val holder = pool.submit {
            postgresDeploymentExecutionGate.withLock(deploymentId) {
                holderEntered.countDown()
                await(holderRelease, "Execution gate holder wasn't released")
            }
        }

        try {
            await(holderEntered, "Execution gate holder didn't acquire its lock")
            val poolMxBean = executionGateDataSource.hikariPoolMXBean
            val totalConnectionsBeforeWaiters = poolMxBean.totalConnections

            val waiters = (1 until poolSize).map {
                pool.submit {
                    postgresDeploymentExecutionGate.withLock(deploymentId) {}
                }
            }

            waiters.forEach { waiter ->
                val failure = assertFailsWith<ExecutionException> {
                    waiter.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
                assertIs<DeploymentExecutionGateLockTimeoutException>(failure.cause)
            }

            assertEquals(1, poolMxBean.activeConnections)
            assertEquals(
                totalConnectionsBeforeWaiters,
                poolMxBean.totalConnections
            )
        } finally {
            holderRelease.countDown()
            holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            terminate(pool, "Execution gate lock waiters didn't terminate")
        }
    }

    @Test
    fun `main database transactions work while execution gate pool is exhausted`() {
        val poolSize = executionGatePoolSize()
        val holdersEntered = CountDownLatch(poolSize)
        val holdersRelease = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(poolSize)
        val holders = submitExecutionGateHolders(
            poolSize = poolSize,
            pool = pool,
            holdersEntered = holdersEntered,
            holdersRelease = holdersRelease
        )

        try {
            await(holdersEntered, "Execution gate pool wasn't exhausted")

            val queryResult = inNewTransaction {
                entityManager.createNativeQuery("SELECT 1").singleResult as Number
            }

            assertEquals(1, queryResult.toInt())
        } finally {
            holdersRelease.countDown()
            awaitSuccessful(holders)
            terminate(pool, "Execution gate pool holders didn't terminate")
        }
    }

    @Test
    fun `pool exhaustion leaves deployment in process when failure cannot be recorded`() {
        val context = createServiceContext()
        val poolSize = executionGatePoolSize()
        val holdersEntered = CountDownLatch(poolSize)
        val holdersRelease = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(poolSize)
        val holders = submitExecutionGateHolders(
            poolSize = poolSize,
            pool = pool,
            holdersEntered = holdersEntered,
            holdersRelease = holdersRelease
        )

        try {
            await(holdersEntered, "Execution gate pool wasn't exhausted")

            val failure = assertFailsWith<DeploymentExecutionCapacityExhaustedException> {
                deployExecutor.deploy(context.serviceId)
            }

            assertIs<DeploymentExecutionCapacityExhaustedException>(
                failure.suppressed.single()
            )
            assertDeploymentRemainsInProcess(context.serviceId)
        } finally {
            holdersRelease.countDown()
            awaitSuccessful(holders)
            terminate(pool, "Execution gate pool holders didn't terminate")
        }
    }

    @Test
    fun `same deployment timeout leaves deployment in process when failure cannot be recorded`() {
        val context = createServiceContext()
        val deploymentPublished =
            controllableDeploymentExecutionGate.armForPublication()
        val holderEntered = CountDownLatch(1)
        val holderRelease = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val deployment = pool.submit<String> {
            deployExecutor.deploy(context.serviceId)
        }

        try {
            await(deploymentPublished, "Deployment ID wasn't published by the gate")
            val deploymentId = assertNotNull(
                controllableDeploymentExecutionGate.publishedDeploymentId
            )

            pool.submit {
                postgresDeploymentExecutionGate.withLock(deploymentId) {
                    holderEntered.countDown()
                    await(holderRelease, "Deployment gate holder wasn't released")
                }
            }
            await(holderEntered, "Deployment gate holder didn't acquire the lock")

            controllableDeploymentExecutionGate.disarmForPublication()

            val failure = assertFailsWith<ExecutionException> {
                deployment.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            val timeout = assertIs<DeploymentOperationInProgressException>(failure.cause)
            assertIs<DeploymentOperationInProgressException>(
                timeout.suppressed.single()
            )
            assertDeploymentRemainsInProcess(context.serviceId)
        } finally {
            controllableDeploymentExecutionGate.disarmForPublication()
            holderRelease.countDown()
            terminate(pool, "Same-deployment timeout workers didn't terminate")
        }
    }

    @Test
    fun `persistent execution gate lock failure leaves deployment in process`() {
        val context = createServiceContext()

        val failure =
            assertFailsWith<DeploymentOperationConcurrencyCheckFailureException> {
                controllableDeploymentExecutionGate.failNext(
                    count = 2,
                    failureFactory = ::simulatedExecutionGateLockFailure
                ) {
                    deployExecutor.deploy(context.serviceId)
                }
            }

        assertIs<DeploymentOperationConcurrencyCheckFailureException>(
            failure.suppressed.single()
        )
        assertDeploymentRemainsInProcess(context.serviceId)
    }

    @Test
    fun `transient execution gate lock failure is recorded as failed`() {
        val context = createServiceContext()

        val failure =
            assertFailsWith<DeploymentOperationConcurrencyCheckFailureException> {
                controllableDeploymentExecutionGate.failNext(
                    count = 1,
                    failureFactory = ::simulatedExecutionGateLockFailure
                ) {
                    deployExecutor.deploy(context.serviceId)
                }
            }

        val deployment = deploymentRepository.findAll()
            .single { it.service.id == context.serviceId }
        assertEquals(ManagedServiceDeploymentStatus.FAILED, deployment.status)
        assertEquals(failure.message, deployment.errorMessage)
    }

    private fun executionGatePoolSize(): Int {
        return environment.getRequiredProperty<Int>(
            "app.datasource.execution-gate.hikari.maximum-pool-size"
        )
    }

    private fun submitExecutionGateHolders(
        poolSize: Int,
        pool: ExecutorService,
        holdersEntered: CountDownLatch,
        holdersRelease: CountDownLatch
    ): List<Future<*>> {
        return (1..poolSize).map {
            pool.submit {
                postgresDeploymentExecutionGate.withLock(UUID.randomUUID()) {
                    holdersEntered.countDown()
                    await(
                        holdersRelease,
                        "Execution gate pool holder wasn't released"
                    )
                }
            }
        }
    }

    private fun awaitSuccessful(futures: Collection<Future<*>>) {
        futures.forEach { future ->
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun assertDeploymentRemainsInProcess(serviceId: UUID) {
        val deployment = deploymentRepository.findActiveByServiceId(serviceId).single()
        assertEquals(ManagedServiceDeploymentStatus.IN_PROCESS, deployment.status)
        assertNull(deployment.errorMessage)
    }

    private fun simulatedExecutionGateLockFailure(): RuntimeException {
        return DeploymentExecutionGateLockFailureException(
            SQLException("Simulated execution gate lock failure")
        )
    }

    private fun createServiceContext(
        volume: ServiceVolumeMount? = null
    ): ServiceContext = inNewTransaction {
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
            serviceId = service.id
        )
    }

    private fun requestCancellation(serviceId: UUID): UUID {
        val deployment = deploymentRepository.findActiveByServiceId(serviceId).single()
        assertEquals(
            ManagedServiceDeploymentCancellationResult.Requested,
            deploymentLifecycleService.requestDeploymentCancellationForDeploymentId(
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

    private fun <T : Any> inNewTransaction(block: () -> T): T {
        return newTransactionTemplate.execute { block() }
    }

    private fun await(latch: CountDownLatch, failureMessage: String) {
        assertTrue(
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            failureMessage
        )
    }

    private fun terminate(
        pool: ExecutorService,
        failureMessage: String
    ) {
        pool.shutdownNow()
        assertTrue(
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            failureMessage
        )
    }

    private fun randomResourceName(prefix: String): String =
        "$prefix-${UUID.randomUUID().toString().replace("-", "").take(8)}"

    private data class ServiceContext(
        val projectId: UUID,
        val serviceId: UUID
    )

    class SimulatedDeployExecutorDeath(
        val deathPoint: FakeDockerAgentClient.ExecutorDeathPoint
    ) : Error("Simulated deploy executor death at $deathPoint")

    class SimulatedContainerStartFailure :
        RuntimeException("Simulated container start failure")

    @TestConfiguration
    class FakeDockerAgentClientConfig {
        @Bean
        @Primary
        fun fakeDockerAgentClient() = FakeDockerAgentClient()
    }

    @TestConfiguration
    class ControllableDeploymentExecutionGateConfig {
        @Bean
        @Primary
        fun controllableDeploymentExecutionGate(
            postgresDeploymentExecutionGate: PostgresDeploymentExecutionGate
        ): ControllableDeploymentExecutionGate {
            return ControllableDeploymentExecutionGate(
                postgresDeploymentExecutionGate
            )
        }
    }

    class ControllableDeploymentExecutionGate(
        private val delegate: DeploymentExecutionGate
    ) : DeploymentExecutionGate {
        private val failures =
            ThreadLocal<ArrayDeque<() -> RuntimeException>>()

        @Volatile
        private var deploymentPublished: CountDownLatch? = null

        @Volatile
        private var afterPublicationAllowContinue: CountDownLatch? = null

        @Volatile
        var publishedDeploymentId: UUID? = null
            private set

        @Synchronized
        fun armForPublication(): CountDownLatch {
            check(deploymentPublished == null) {
                "Deployment publication is already armed"
            }

            val deploymentPublished = CountDownLatch(1)
            this.deploymentPublished = deploymentPublished
            afterPublicationAllowContinue = CountDownLatch(1)
            publishedDeploymentId = null

            return deploymentPublished
        }

        @Synchronized
        fun disarmForPublication() {
            afterPublicationAllowContinue?.countDown()
            deploymentPublished = null
            afterPublicationAllowContinue = null
        }

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
            deploymentId: UUID,
            block: () -> T
        ): T {
            publishDeploymentIdIfArmed(deploymentId)
            throwNextFailureIfConfigured()
            return delegate.withLock(deploymentId, block)
        }

        override fun <T> withLock(
            deploymentId: UUID,
            timeout: Duration,
            block: () -> T
        ): T {
            publishDeploymentIdIfArmed(deploymentId)
            throwNextFailureIfConfigured()
            return delegate.withLock(deploymentId, timeout, block)
        }

        private fun publishDeploymentIdIfArmed(deploymentId: UUID) {
            val deploymentPublished = deploymentPublished ?: return
            val afterPublicationAllowContinue =
                afterPublicationAllowContinue ?: return

            publishedDeploymentId = deploymentId
            deploymentPublished.countDown()
            assertTrue(
                afterPublicationAllowContinue.await(
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                ),
                "Deployment execution wasn't allowed to continue after publication"
            )
        }

        private fun throwNextFailureIfConfigured() {
            val configuredFailures = failures.get() ?: return
            if (configuredFailures.isNotEmpty()) {
                throw configuredFailures.removeFirst().invoke()
            }
        }
    }

    class FakeDockerAgentClient : DockerAgentClient {
        val pulledImages: MutableSet<DockerImageRef> = ConcurrentHashMap.newKeySet()
        val createdNetworks = ConcurrentHashMap<String, DockerNetworkRef>()
        val networksByName = ConcurrentHashMap<String, DockerNetworkRef>()
        val createdVolumes = ConcurrentHashMap<String, DockerVolumeRef>()
        val activeVolumeNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val removedVolumeNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val createdContainerCommands =
            ConcurrentHashMap<String, DockerContainerCreateCommand>()
        val activeContainerIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val deletedContainerIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val containerStates =
            ConcurrentHashMap<String, DockerContainerState>()

        @Volatile
        var createContainerEntered: CountDownLatch? = null

        @Volatile
        var containerCreationAllowed: CountDownLatch? = null

        @Volatile
        var afterCreateContainer: CountDownLatch? = null

        @Volatile
        var afterCreateContainerContinueAllowed: CountDownLatch? = null

        @Volatile
        var startContainerEntered: CountDownLatch? = null

        @Volatile
        var startContainerContinueAllowed: CountDownLatch? = null

        @Volatile
        var startContainerFailure: RuntimeException? = null

        @Volatile
        var executorDeathPoint: ExecutorDeathPoint? = null

        @Volatile
        var createVolumeEntered: CountDownLatch? = null

        @Volatile
        var volumeCreationAllowed: CountDownLatch? = null

        @Volatile
        var afterCreateVolume: CountDownLatch? = null

        @Volatile
        var afterCreateVolumeContinueAllowed: CountDownLatch? = null

        @Volatile
        var createNetworkEntered: CountDownLatch? = null

        @Volatile
        var networkCreationAllowed: CountDownLatch? = null

        @Volatile
        var afterCreateNetwork: CountDownLatch? = null

        @Volatile
        var afterCreateNetworkContinueAllowed: CountDownLatch? = null

        fun reset() {
            pulledImages.clear()
            createdNetworks.clear()
            networksByName.clear()
            createdVolumes.clear()
            activeVolumeNames.clear()
            removedVolumeNames.clear()
            createdContainerCommands.clear()
            activeContainerIds.clear()
            deletedContainerIds.clear()
            containerStates.clear()
            createContainerEntered = null
            containerCreationAllowed = null
            afterCreateContainer = null
            afterCreateContainerContinueAllowed = null
            startContainerEntered = null
            startContainerContinueAllowed = null
            startContainerFailure = null
            executorDeathPoint = null
            createVolumeEntered = null
            volumeCreationAllowed = null
            afterCreateVolume = null
            afterCreateVolumeContinueAllowed = null
            createNetworkEntered = null
            networkCreationAllowed = null
            afterCreateNetwork = null
            afterCreateNetworkContinueAllowed = null
        }

        override fun status(): DockerAgentInfo {
            error("Docker agent status flow not used in this test")
        }

        override fun listImages(): List<DockerImageSummary> {
            error("Docker image listing flow not used in this test")
        }

        override fun inspectImage(imageId: String): DockerImageInfo {
            error("Docker image inspection flow not used in this test")
        }

        override fun pullImage(imageRef: DockerImageRef) {
            pulledImages.add(imageRef)
        }

        override fun removeImage(imageId: String, force: Boolean) {
            error("Docker image removal flow not used in this test")
        }

        override fun findNetworkByName(name: String): DockerNetworkRef? {
            return networksByName[name]
        }

        override fun createNetwork(
            name: String,
            labels: Map<String, String>
        ): DockerNetworkRef {
            createNetworkEntered?.countDown()
            awaitPermission(
                networkCreationAllowed,
                "Network creation allowed latch wasn't released"
            )

            val network = DockerNetworkRef(
                id = UUID.randomUUID().toString(),
                name = name,
                labels = labels.toMap()
            )
            createdNetworks[network.id] = network
            networksByName[name] = network
            afterCreateNetwork?.countDown()
            awaitPermission(
                afterCreateNetworkContinueAllowed,
                "Network creation continuation latch wasn't released"
            )
            return network
        }

        override fun removeNetwork(networkId: String) {
            error("Docker network removal flow not used in this test")
        }

        override fun createVolume(
            name: String,
            labels: Map<String, String>
        ) {
            createVolumeEntered?.countDown()
            awaitPermission(
                volumeCreationAllowed,
                "Volume creation allowed latch wasn't released"
            )

            val volume = DockerVolumeRef(
                name = name,
                labels = labels.toMap()
            )
            createdVolumes[name] = volume
            activeVolumeNames.add(name)
            afterCreateVolume?.countDown()
            awaitPermission(
                afterCreateVolumeContinueAllowed,
                "Volume creation continuation latch wasn't released"
            )
        }

        override fun findVolumeByName(name: String): DockerVolumeRef? {
            if (!activeVolumeNames.contains(name)) {
                return null
            }
            return createdVolumes[name]
        }

        override fun listVolumes(filter: DockerLabelsFilter): List<DockerVolumeRef> {
            return activeVolumeNames.mapNotNull { name ->
                createdVolumes[name]?.takeIf { it.labels.matches(filter) }
            }
        }

        override fun removeVolume(name: String) {
            if (!activeVolumeNames.remove(name)) {
                throw DockerVolumeNotFoundException("Docker volume $name wasn't found")
            }
            removedVolumeNames.add(name)
        }

        override fun createContainer(command: DockerContainerCreateCommand): String {
            createContainerEntered?.countDown()
            awaitPermission(
                containerCreationAllowed,
                "Container creation allowed latch wasn't released"
            )

            val containerId = UUID.randomUUID().toString()
            createdContainerCommands[containerId] = command.copy(
                env = command.env.toMap(),
                labels = command.labels.toMap(),
                command = command.command?.toList(),
                args = command.args?.toList(),
                networkAliases = command.networkAliases.toList(),
                portBindings = command.portBindings.toList(),
                volumeBindings = command.volumeBindings.toList()
            )
            activeContainerIds.add(containerId)
            containerStates[containerId] = DockerContainerState.CREATED
            afterCreateContainer?.countDown()
            awaitPermission(
                afterCreateContainerContinueAllowed,
                "Container creation continuation latch wasn't released"
            )
            throwIfExecutorDiesAt(
                ExecutorDeathPoint.AFTER_CONTAINER_CREATION
            )
            return containerId
        }

        override fun listContainers(
            filter: DockerLabelsFilter
        ): List<DockerContainerSummary> {
            return activeContainerIds.mapNotNull { containerId ->
                val command = createdContainerCommands[containerId]
                    ?: return@mapNotNull null
                if (!command.labels.matches(filter)) {
                    return@mapNotNull null
                }

                DockerContainerSummary(
                    id = containerId,
                    name = command.name,
                    names = listOf(command.name),
                    image = "${command.image.repository}:${command.image.tag}",
                    imageId = null,
                    command = command.command?.joinToString(" "),
                    createdEpochSeconds = null,
                    state = containerStates[containerId]
                        ?: DockerContainerState.UNKNOWN,
                    status = null,
                    ports = emptyList(),
                    labels = command.labels,
                    sizeRwBytes = null,
                    sizeRootFsBytes = null,
                    networkMode = command.networkName,
                    networks = emptyMap(),
                    mounts = emptyList()
                )
            }
        }

        override fun inspectContainer(containerId: String): DockerContainerDetails {
            error("Docker container inspection flow not used in this test")
        }

        override fun getContainerStats(containerId: String): DockerContainerStats {
            error("Docker container stats flow not used in this test")
        }

        override fun getLogs(
            containerId: String,
            request: DockerContainerLogsRequest
        ): DockerContainerLogs {
            error("Docker container logs flow not used in this test")
        }

        override fun startContainer(containerId: String) {
            if (!activeContainerIds.contains(containerId)) {
                throw DockerContainerNotFoundException(
                    "Docker container $containerId wasn't found"
                )
            }
            startContainerEntered?.countDown()
            awaitPermission(
                startContainerContinueAllowed,
                "Container start continuation latch wasn't released"
            )
            startContainerFailure?.let { throw it }
            containerStates[containerId] = DockerContainerState.RUNNING
            throwIfExecutorDiesAt(ExecutorDeathPoint.AFTER_CONTAINER_START)
        }

        override fun stopContainer(containerId: String) {
            error("Docker container stop flow not used in this test")
        }

        override fun restartContainer(containerId: String) {
            error("Docker container restart flow not used in this test")
        }

        override fun pauseContainer(containerId: String) {
            error("Docker container pause flow not used in this test")
        }

        override fun unpauseContainer(containerId: String) {
            error("Docker container unpause flow not used in this test")
        }

        override fun deleteContainer(containerId: String) {
            if (!activeContainerIds.remove(containerId)) {
                throw DockerContainerNotFoundException(
                    "Docker container $containerId wasn't found"
                )
            }
            containerStates.remove(containerId)
            deletedContainerIds.add(containerId)
        }

        private fun throwIfExecutorDiesAt(deathPoint: ExecutorDeathPoint) {
            if (executorDeathPoint == deathPoint) {
                throw SimulatedDeployExecutorDeath(deathPoint)
            }
        }

        private fun awaitPermission(
            latch: CountDownLatch?,
            failureMessage: String
        ) {
            if (latch == null) {
                return
            }
            assertTrue(
                latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                failureMessage
            )
        }

        private fun Map<String, String>.matches(
            filter: DockerLabelsFilter
        ): Boolean {
            return filter.labels.all { (name, value) -> this[name] == value }
        }

        enum class ExecutorDeathPoint {
            AFTER_CONTAINER_CREATION,
            AFTER_CONTAINER_START
        }
    }
}
