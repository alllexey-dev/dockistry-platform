package io.dockistry.platform.services.application

import com.github.dockerjava.api.DockerClient
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.docker.infrastructure.DockerAgentProperties
import io.dockistry.platform.docker.infrastructure.LocalDockerAgentClient
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.projects.infrastructure.ProjectRepository
import io.dockistry.platform.services.application.dto.ManagedServiceCreateCommand
import io.dockistry.platform.services.application.dto.ManagedServiceUpdateCommand
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceAlreadyExistsException
import io.dockistry.platform.services.domain.ManagedServiceConfigurationDraft
import io.dockistry.platform.services.domain.ManagedServiceDeploymentStatus
import io.dockistry.platform.services.domain.ManagedServiceOperationInProgressException
import io.dockistry.platform.services.infrastructure.ManagedServiceDeploymentRepository
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import io.dockistry.platform.services.testsupport.managedService
import io.dockistry.platform.services.testsupport.managedServiceDraft
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val TIMEOUT_SECONDS = 15L

@SpringBootTest
@Import(
    TestcontainersConfig::class,
    ManagedServiceConcurrencyIntegrationTests.ConcurrencyTestConfig::class
)
@Testcontainers
class ManagedServiceConcurrencyIntegrationTests(
    @Autowired private val projectRepository: ProjectRepository
) {

    @Autowired
    private lateinit var managedServiceService: ManagedServiceService

    @Autowired
    private lateinit var managedServiceLifecycleGuard: ManagedServiceLifecycleGuard

    @Autowired
    private lateinit var deploymentLifecycleService: ManagedServiceDeploymentLifecycleService

    @Autowired
    private lateinit var runtimeLifecycleService: ManagedServiceRuntimeLifecycleService

    @Autowired
    private lateinit var runtimeExecutor: ManagedServiceRuntimeExecutor

    @Autowired
    private lateinit var managedServiceRepository: ManagedServiceRepository

    @Autowired
    private lateinit var deploymentRepository: ManagedServiceDeploymentRepository

    @Autowired
    private lateinit var dockerAgentClient: EmptyContainerDockerAgentClient

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var clock: BarrierClock

    /**
     * Runs fixture setup and database verification in committed, independent
     * transactions. Worker tasks deliberately do not use this template: they call
     * [ManagedServiceService] directly so Spring opens the same transaction boundaries
     * that production requests use.
     *
     * `REQUIRES_NEW` guarantees that setup data is committed and visible before worker
     * threads start, and that readback observes the state committed by those workers.
     * The template is lazy because [transactionManager] is field-injected after the test
     * instance is constructed; the first test-method call happens after injection.
     */
    private val newTransactionTemplate: TransactionTemplate by lazy {
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }
    }

    @Test
    fun `update waits for managed service row lock and succeeds after release`() {
        val context = createServiceContext()
        val command = updateCommand(
            context,
            managedServiceDraft(imageRef = "redis:7")
        )
        withServiceRowLock(context.serviceId) { releaseLock ->
            val updateStarted = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val update = executor.submit<ManagedService> {
                updateStarted.countDown()
                managedServiceService.update(command)
            }

            try {
                assertTrue(
                    updateStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Managed service update wasn't started"
                )
                assertFailsWith<TimeoutException> {
                    update.get(200, TimeUnit.MILLISECONDS)
                }

                releaseLock()
                val updated = update.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertEquals("redis:7", updated.imageRef.asString())
            } finally {
                releaseLock()
                executor.shutdownNow()
                assertTrue(
                    executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Managed service update executor didn't terminate"
                )
            }
        }

        val saved = serviceSnapshot(context.serviceId)
        assertEquals("redis:7", saved.imageRef)
        assertEquals(1, saved.version)
    }

    @Test
    fun `bounded lifecycle lock fails after configured timeout`() {
        val context = createServiceContext()
        val lockTimeout = Duration.ofMillis(100)

        withServiceRowLock(context.serviceId) {
            val startedAt = System.nanoTime()
            assertFailsWith<ManagedServiceOperationInProgressException> {
                inNewTransaction {
                    managedServiceLifecycleGuard.requireActiveForMutationWithWait(
                        serviceId = context.serviceId,
                        lockTimeout = lockTimeout
                    )
                }
            }
            val waitDuration = Duration.ofNanos(System.nanoTime() - startedAt)
            assertTrue(
                waitDuration.toMillis() >= 100,
                "Lifecycle lock failed before waiting for the configured timeout"
            )
        }
    }

    @Test
    fun `fail-fast lifecycle lock remains available`() {
        val context = createServiceContext()

        withServiceRowLock(context.serviceId) {
            assertFailsWith<ManagedServiceOperationInProgressException> {
                inNewTransaction {
                    managedServiceLifecycleGuard.requireActiveForMutation(
                        context.serviceId
                    )
                }
            }
        }
    }

    @Test
    fun `start is blocked while deployment is active`() {
        val context = createServiceContext()
        deploymentLifecycleService.begin(context.serviceId)

        assertFailsWith<ManagedServiceOperationInProgressException> {
            runtimeLifecycleService.beginStart(context.serviceId)
        }
    }

    @Test
    fun `stop captures active deployment in operation context`() {
        val context = createServiceContext()
        val deployment = deploymentLifecycleService.begin(context.serviceId)

        val operation = runtimeLifecycleService.beginStop(context.serviceId)

        assertEquals(deployment.deploymentId, operation.deploymentId)
    }

    @Test
    fun `stop does not cancel deployment that became active after begin stop captured another deployment`() {
        val context = createServiceContext()
        val replacementDeployment = deploymentLifecycleService.begin(context.serviceId)
        deploymentLifecycleService.fail(
            deploymentId = replacementDeployment.deploymentId,
            errorMessage = "Prepared replacement deployment"
        )
        val capturedDeployment = deploymentLifecycleService.begin(context.serviceId)
        assertNotEquals(
            replacementDeployment.deploymentId,
            capturedDeployment.deploymentId
        )
        assertEquals(
            listOf(capturedDeployment.deploymentId),
            activeDeploymentIds(context.serviceId)
        )

        val instantLatch = clock.armLatch()
        val executor = Executors.newSingleThreadExecutor()
        val stop = executor.submit {
            clock.coordinate {
                runtimeExecutor.stop(context.serviceId)
            }
        }

        try {
            assertTrue(
                instantLatch.instantEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Stop didn't reach beginStop's clock call"
            )

            replaceActiveDeployment(
                capturedDeploymentId = capturedDeployment.deploymentId,
                replacementDeploymentId = replacementDeployment.deploymentId
            )

            assertEquals(
                listOf(replacementDeployment.deploymentId),
                activeDeploymentIds(context.serviceId)
            )

            instantLatch.instantContinue.countDown()
            stop.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            instantLatch.instantContinue.countDown()
            executor.shutdownNow()
            assertTrue(
                executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Stop executor didn't terminate"
            )
            clock.disarm()
        }

        assertEquals(
            ManagedServiceDeploymentStatus.CANCELED,
            deploymentStatus(capturedDeployment.deploymentId)
        )
        assertEquals(
            ManagedServiceDeploymentStatus.IN_PROCESS,
            deploymentStatus(replacementDeployment.deploymentId)
        )
    }

    @Test
    fun `concurrent same name creates produce one service and one typed conflict`() {
        val context = createProjectContext()
        val command = ManagedServiceCreateCommand(
            projectId = context.projectId,
            principal = context.principal,
            name = "concurrent-api",
            configuration = managedServiceDraft()
        )

        val results = runConcurrently(
            { managedServiceService.create(command) },
            { managedServiceService.create(command) }
        )

        val successes = results.filter { it.isSuccess }
        val failures = results.mapNotNull { it.exceptionOrNull() }
        assertEquals(1, successes.size)
        assertEquals(1, failures.size)
        assertInstanceOf<ManagedServiceAlreadyExistsException>(failures.single())
        assertEquals(
            1,
            countServices(context.projectId, "concurrent-api")
        )
    }

    private fun updateCommand(
        context: ExistingServiceContext,
        draft: ManagedServiceConfigurationDraft
    ): ManagedServiceUpdateCommand {
        return ManagedServiceUpdateCommand(
            projectId = context.projectId,
            serviceId = context.serviceId,
            principal = context.principal,
            expectedVersion = 0,
            configuration = draft
        )
    }

    private fun createServiceContext(): ExistingServiceContext = inNewTransaction {
        val projectContext = createProjectContextInternal()
        val project = projectRepository.findById(projectContext.projectId).orElseThrow()
        val service = managedServiceRepository.saveAndFlush(
            managedService(
                project = project,
                name = "service-${UUID.randomUUID().toString().take(8)}"
            )
        )

        ExistingServiceContext(
            projectId = projectContext.projectId,
            serviceId = service.id,
            principal = projectContext.principal
        )
    }

    private fun createProjectContext(): ProjectContext = inNewTransaction {
        createProjectContextInternal()
    }

    private fun serviceSnapshot(serviceId: UUID): ManagedServiceSnapshot =
        inNewTransaction {
            val service = managedServiceRepository.findById(serviceId).orElseThrow()
            ManagedServiceSnapshot(
                imageRef = service.imageRef.asString(),
                version = service.version
            )
        }

    private fun countServices(projectId: UUID, name: String): Long =
        inNewTransaction {
            managedServiceRepository.findAllByProjectId(projectId)
                .count { it.name == name }
                .toLong()
        }

    private fun activeDeploymentIds(serviceId: UUID): List<UUID> =
        inNewTransaction {
            deploymentRepository.findActiveIdsByServiceId(serviceId)
        }

    private fun deploymentStatus(deploymentId: UUID): ManagedServiceDeploymentStatus =
        inNewTransaction {
            deploymentRepository.findById(deploymentId).orElseThrow().status
        }

    /**
     * Models the active deployment changing after `beginStop` captured its ID, but before
     * that transaction persisted the service transition. A regular deployment begin
     * cannot create this deliberately adversarial state while the service row is locked,
     * so the test promotes a pre-existing terminal deployment with direct deployment-row
     * CAS updates.
     */
    private fun replaceActiveDeployment(
        capturedDeploymentId: UUID,
        replacementDeploymentId: UUID
    ) {
        inNewTransaction {
            val updatedAt = clock.instant()
            val canceledRows = entityManager.createNativeQuery("""
                UPDATE service_deployments
                SET status = 'CANCELED',
                    cleanup_error_message = NULL,
                    last_cleanup_attempt_at = :updatedAt,
                    updated_at = :updatedAt
                WHERE id = :deploymentId
                  AND status = 'IN_PROCESS'
            """.trimIndent())
                .setParameter("deploymentId", capturedDeploymentId)
                .setParameter("updatedAt", updatedAt)
                .executeUpdate()
            assertEquals(1, canceledRows)

            val activatedRows = entityManager.createNativeQuery("""
                UPDATE service_deployments
                SET status = 'IN_PROCESS',
                    error_message = NULL,
                    updated_at = :updatedAt
                WHERE id = :deploymentId
                  AND status = 'FAILED'
            """.trimIndent())
                .setParameter("deploymentId", replacementDeploymentId)
                .setParameter("updatedAt", updatedAt)
                .executeUpdate()
            assertEquals(1, activatedRows)
        }
    }

    private fun createProjectContextInternal(): ProjectContext {
        val fixtures = IamProjectFixtures(entityManager)
        val principal = fixtures.principal()
        val project = fixtures.project(ownerPrincipal = principal)
        fixtures.grantSystemPermission(
            principal,
            project.scope,
            SystemPermissionNames.PROJECT_READ.key,
            SystemPermissionNames.PROJECT_SERVICES_MANAGE.key
        )
        fixtures.flush()

        return ProjectContext(
            projectId = project.id,
            principal = principal
        )
    }

    private fun <T : Any> inNewTransaction(block: () -> T): T {
        return checkNotNull(newTransactionTemplate.execute { block() })
    }

    private fun <T> withServiceRowLock(
        serviceId: UUID,
        block: (() -> Unit) -> T
    ): T {
        val lockAcquired = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val lockHolder = executor.submit {
            inNewTransaction {
                assertNotNull(managedServiceRepository.findByIdForUpdateNowait(serviceId))
                lockAcquired.countDown()
                assertTrue(
                    releaseLock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Managed service row lock holder wasn't released"
                )
            }
        }

        try {
            assertTrue(
                lockAcquired.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Managed service row lock wasn't acquired"
            )
            return block { releaseLock.countDown() }
        } finally {
            releaseLock.countDown()
            try {
                lockHolder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
                assertTrue(
                    executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Managed service row lock holder didn't terminate"
                )
            }
        }
    }

    /**
     * Executes use-case calls on separate worker threads and captures each result without
     * wrapping the workers in a test-managed transaction.
     *
     * [BarrierClock.arm] configures the critical-point rendezvous before tasks are
     * submitted. [BarrierClock.coordinate] then marks each worker as a participant. The
     * production create executor eventually calls `Clock.instant()` after its name
     * pre-check, but immediately before persisting the service. Both workers therefore
     * reach the write boundary before either can insert, guaranteeing the unique-key race.
     *
     * There is intentionally no second barrier at task startup: synchronizing at the
     * critical database boundary is sufficient. `Result` preserves both successful
     * entities and domain exceptions for assertions. The `finally` block always clears
     * the clock and terminates workers so a failed task cannot contaminate another test.
     */
    private fun runConcurrently(
        vararg tasks: () -> ManagedService
    ): List<Result<ManagedService>> {
        val executor = Executors.newFixedThreadPool(tasks.size)
        clock.arm(tasks.size)

        return try {
            val futures = tasks.map { task ->
                executor.submit<Result<ManagedService>> {
                    runCatching { clock.coordinate(task) }
                }
            }

            futures.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            clock.disarm()
            executor.shutdownNow()
            assertTrue(
                executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Concurrency test workers did not terminate"
            )
        }
    }

    @TestConfiguration
    class ConcurrencyTestConfig {
        @Bean
        @Primary
        fun barrierClock(): BarrierClock {
            return BarrierClock(Instant.parse("2026-01-01T00:00:00Z"))
        }

        @Bean
        @Primary
        fun fakeDockerAgentClient(
            dockerClient: DockerClient,
            dockerAgentProperties: DockerAgentProperties
        ): EmptyContainerDockerAgentClient {
            return EmptyContainerDockerAgentClient(
                dockerClient = dockerClient,
                dockerAgentProperties = dockerAgentProperties
            )
        }
    }
}

class EmptyContainerDockerAgentClient(
    dockerClient: DockerClient,
    dockerAgentProperties: DockerAgentProperties
) : LocalDockerAgentClient(dockerClient, dockerAgentProperties) {
    override fun listContainers(
        filter: DockerLabelsFilter
    ): List<DockerContainerSummary> {
        return emptyList()
    }
}

/**
 * Fixed test clock that also provides deterministic coordination at production write
 * boundaries.
 *
 * `ManagedServiceCreateExecutor` obtains the current time after its pre-check and
 * immediately before creating persistent state. Registering this clock as `@Primary`
 * lets the test pause both create requests at that point without adding test hooks to
 * production code or relying on thread timing.
 *
 * The Spring context may use the same primary [Clock] for unrelated work. Only threads
 * wrapped by [coordinate] participate in an armed barrier or latch; every other caller
 * receives the fixed time immediately. Volatile coordination state publishes the
 * primitive installed by the test thread to worker threads.
 */
class BarrierClock(
    private val current: Instant
) : Clock() {
    @Volatile
    private var barrier: CyclicBarrier? = null

    @Volatile
    private var instantLatch: InstantLatch? = null

    private val participatingThread = ThreadLocal.withInitial { false }

    /**
     * Installs the barrier used by the next concurrent run.
     *
     * Re-arming before [disarm] indicates a broken test lifecycle and is rejected rather
     * than silently replacing a barrier that workers may still be using.
     */
    fun arm(parties: Int) {
        check(barrier == null && instantLatch == null) {
            "Barrier clock is already armed"
        }
        barrier = CyclicBarrier(parties)
    }

    /**
     * Installs one-way latches for a worker that must pause at its next clock call.
     *
     * The test waits for [InstantLatch.instantEntered], changes the concurrent database
     * state, then releases [InstantLatch.instantContinue]. The same continue latch may
     * safely be observed by later clock calls in that worker because it stays open.
     */
    fun armLatch(): InstantLatch {
        check(barrier == null && instantLatch == null) {
            "Barrier clock is already armed"
        }
        return InstantLatch().also {
            instantLatch = it
        }
    }

    /**
     * Marks the current worker as a barrier participant for the duration of [block].
     *
     * The marker is thread-local so unrelated Spring threads never join the rendezvous.
     * It is removed in `finally` to keep pooled worker threads reusable after failures.
     */
    fun <T> coordinate(block: () -> T): T {
        participatingThread.set(true)
        return try {
            block()
        } finally {
            participatingThread.remove()
        }
    }

    /**
     * Clears active coordination after a concurrent run, including exceptional exits,
     * so no synchronization state leaks into the next test.
     */
    fun disarm() {
        barrier = null
        instantLatch = null
    }

    /**
     * Returns [current] immediately for ordinary callers. Participating workers wait on
     * the armed latch or until every barrier party reaches the same production clock
     * call. The timeout converts missing coordination into a bounded test failure.
     */
    override fun instant(): Instant {
        if (!participatingThread.get()) {
            return current
        }

        instantLatch?.let {
            it.instantEntered.countDown()
            check(it.instantContinue.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting to continue from coordinated clock call"
            }
            return current
        }

        val currentBarrier = checkNotNull(barrier) {
            "Barrier clock was not armed"
        }
        currentBarrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return current
    }

    /** Returns the UTC zone used by this fixed test clock. */
    override fun getZone(): ZoneId = ZoneOffset.UTC

    /**
     * Keeps coordination behavior when UTC is requested. Other zones receive a regular
     * fixed clock because coordination is scoped to the injected UTC test bean.
     */
    override fun withZone(zone: ZoneId): Clock {
        return if (zone == ZoneOffset.UTC) this else fixed(current, zone)
    }
}

class InstantLatch(
    val instantEntered: CountDownLatch = CountDownLatch(1),
    val instantContinue: CountDownLatch = CountDownLatch(1)
)

private data class ProjectContext(
    val projectId: UUID,
    val principal: Principal
)

private data class ExistingServiceContext(
    val projectId: UUID,
    val serviceId: UUID,
    val principal: Principal
)

private data class ManagedServiceSnapshot(
    val imageRef: String,
    val version: Long
)
