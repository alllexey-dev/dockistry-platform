package io.dockistry.platform.services.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.projects.infrastructure.ProjectRepository
import io.dockistry.platform.services.application.dto.ManagedServiceCreateCommand
import io.dockistry.platform.services.application.dto.ManagedServiceUpdateCommand
import io.dockistry.platform.services.domain.ManagedServiceAlreadyExistsException
import io.dockistry.platform.services.domain.ManagedServiceConfigurationDraft
import io.dockistry.platform.services.domain.ManagedServiceDeploymentStatus
import io.dockistry.platform.services.domain.ManagedServiceOperationInProgressException
import io.dockistry.platform.services.infrastructure.ManagedServiceDeploymentRepository
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import io.dockistry.platform.services.testsupport.managedService
import io.dockistry.platform.services.testsupport.managedServiceDraft
import io.dockistry.platform.testsupport.IamProjectFixtures
import io.dockistry.platform.testsupport.concurrency.ManagedServiceConcurrencyTestConfiguration
import io.dockistry.platform.testsupport.concurrency.ManagedServiceConcurrencyFixtures
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_SERVICE_ROW_LOCK
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.CLOCK_INSTANT
import io.dockistry.platform.testsupport.concurrency.ConcurrencyTestSupport
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

@SpringBootTest
@Import(
    TestcontainersConfig::class,
    ManagedServiceConcurrencyTestConfiguration::class
)
@Testcontainers
class ManagedServiceConcurrencyIntegrationTests @Autowired constructor(
    private val projectRepository: ProjectRepository
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
    private lateinit var deploymentFixtures: ManagedServiceConcurrencyFixtures

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var concurrency: ConcurrencyTestSupport

    @Test
    fun `update waits for managed service row lock and succeeds after release`() {
        val context = createServiceContext()
        val command = updateCommand(
            context,
            managedServiceDraft(imageRef = "redis:7")
        )
        concurrency.run {
            val rowLock = holdInTransaction("service row lock") {
                assertNotNull(managedServiceRepository.findByIdForUpdateNowait(context.serviceId))
            }
            val updateLock = observeNext(BEFORE_SERVICE_ROW_LOCK)
            val update = start("update service") { managedServiceService.update(command) }
            assertBlocked(updateLock, by = rowLock)

            rowLock.release()
            assertEquals("redis:7", update.await().imageRef.asString())
        }

        val saved = serviceSnapshot(context.serviceId)
        assertEquals("redis:7", saved.imageRef)
        assertEquals(1, saved.version)
    }

    @Test
    fun `bounded lifecycle lock fails after configured timeout`() {
        val context = createServiceContext()
        val lockTimeout = Duration.ofMillis(100)

        concurrency.run {
            holdInTransaction("service row lock") {
                assertNotNull(managedServiceRepository.findByIdForUpdateNowait(context.serviceId))
            }
            concurrency.assertTakesAtLeast(lockTimeout) {
                assertFailsWith<ManagedServiceOperationInProgressException> {
                    concurrency.inNewTransaction {
                        managedServiceLifecycleGuard.requireActiveForMutationWithWait(
                            serviceId = context.serviceId,
                            lockTimeout = lockTimeout
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `fail-fast lifecycle lock remains available`() {
        val context = createServiceContext()
        concurrency.run {
            holdInTransaction("service row lock") {
                assertNotNull(managedServiceRepository.findByIdForUpdateNowait(context.serviceId))
            }
            assertFailsWith<ManagedServiceOperationInProgressException> {
                concurrency.inNewTransaction {
                    managedServiceLifecycleGuard.requireActiveForMutation(context.serviceId)
                }
            }
        }
    }

    @Test
    fun `start is blocked while deployment is active`() {
        val context = createServiceContext()
        deploymentLifecycleService.begin(
            context.serviceId,
            context.principal,
            ignoreWarnings = false
        )

        assertFailsWith<ManagedServiceOperationInProgressException> {
            runtimeLifecycleService.beginStart(context.serviceId)
        }
    }

    @Test
    fun `stop captures active deployment in operation context`() {
        val context = createServiceContext()
        val deployment = deploymentLifecycleService.begin(
            context.serviceId,
            context.principal,
            ignoreWarnings = false
        )

        val operation = runtimeLifecycleService.beginStop(context.serviceId)

        assertEquals(deployment.deploymentId, operation.deploymentId)
    }

    @Test
    fun `stop does not cancel deployment that became active after begin stop captured another deployment`() {
        val context = createServiceContext()
        val replacementDeployment = deploymentLifecycleService.begin(
            context.serviceId,
            context.principal,
            ignoreWarnings = false
        )
        deploymentLifecycleService.fail(
            deploymentId = replacementDeployment.deploymentId,
            errorMessage = "Prepared replacement deployment"
        )
        val capturedDeployment = deploymentLifecycleService.begin(
            context.serviceId,
            context.principal,
            ignoreWarnings = false
        )
        assertNotEquals(
            replacementDeployment.deploymentId,
            capturedDeployment.deploymentId
        )
        assertEquals(
            listOf(capturedDeployment.deploymentId),
            activeDeploymentIds(context.serviceId)
        )

        concurrency.run {
            // beginStop has captured the deployment ID before reading the clock.
            val beginStop = pauseAt(CLOCK_INSTANT)
            val stop = start("stop service") { runtimeExecutor.stop(context.serviceId) }
            beginStop.awaitReached()

            deploymentFixtures.replaceActiveDeployment(
                capturedDeploymentId = capturedDeployment.deploymentId,
                replacementDeploymentId = replacementDeployment.deploymentId
            )
            assertEquals(
                listOf(replacementDeployment.deploymentId),
                activeDeploymentIds(context.serviceId)
            )

            beginStop.release()
            stop.await()
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

        val results = concurrency.run {
            // Both requests have passed the name pre-check when create reads the clock.
            val beforeInsert = pauseAt(CLOCK_INSTANT, participants = 2)
            val creates = List(2) {
                start("create same-name service") { managedServiceService.create(command) }
            }
            beforeInsert.awaitReached()
            beforeInsert.release()
            creates.map { it.outcome() }
        }

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

    private fun createServiceContext(): ExistingServiceContext = concurrency.inNewTransaction {
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

    private fun createProjectContext(): ProjectContext = concurrency.inNewTransaction {
        createProjectContextInternal()
    }

    private fun serviceSnapshot(serviceId: UUID): ManagedServiceSnapshot =
        concurrency.inNewTransaction {
            val service = managedServiceRepository.findById(serviceId).orElseThrow()
            ManagedServiceSnapshot(
                imageRef = service.imageRef.asString(),
                version = service.version
            )
        }

    private fun countServices(projectId: UUID, name: String): Long =
        concurrency.inNewTransaction {
            managedServiceRepository.findAllByProjectId(projectId)
                .count { it.name == name }
                .toLong()
        }

    private fun activeDeploymentIds(serviceId: UUID): List<UUID> =
        concurrency.inNewTransaction {
            deploymentRepository.findActiveIdsByServiceId(serviceId)
        }

    private fun deploymentStatus(deploymentId: UUID): ManagedServiceDeploymentStatus =
        concurrency.inNewTransaction {
            deploymentRepository.findById(deploymentId).orElseThrow().status
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

}

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
