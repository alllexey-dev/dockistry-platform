package io.dockistry.platform.services.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.ProjectNotFoundException
import io.dockistry.platform.projects.infrastructure.ProjectRepository
import io.dockistry.platform.services.application.dto.ManagedServiceCreateCommand
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.testsupport.managedServiceDraft
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
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
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

private const val TIMEOUT = 15L

@SpringBootTest
@Testcontainers
@Import(
    TestcontainersConfig::class,
    ManagedServiceProjectDeleteIntegrationTests.LatchClockConfiguration::class
)
class ManagedServiceProjectDeleteIntegrationTests(
    @Autowired transactionManager: PlatformTransactionManager,

    @Autowired private val entityManager: EntityManager,
    @Autowired private val latchClock: LatchClock,
    @Autowired private val projectRepository: ProjectRepository,
    @Autowired private val managedServiceService: ManagedServiceService
) {
    private val transactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @Test
    fun `service create returns 404 after concurrent project delete wins`() {
        val context = persistProjectContext()
        val pool = Executors.newSingleThreadExecutor()

        val future = pool.submit<Result<ManagedService>> {
            runCatching {
                latchClock.coordinate {
                    managedServiceService.create(
                        ManagedServiceCreateCommand(
                            projectId = context.project.id,
                            principal = context.owner,
                            name = "blocking-service",
                            configuration = managedServiceDraft()
                        )
                    )
                }
            }
        }

        latchClock.waitForReached()
        try {
            inNewTransaction {
                projectRepository.delete(context.project)
            }
        } finally {
            latchClock.release()
        }

        try {
            requireNotNull(
                future.get(
                    TIMEOUT,
                    TimeUnit.SECONDS
                ).exceptionOrNull(),
            ) { "Expected exception from thread call" }
                .let {
                    assertInstanceOf<ProjectNotFoundException>(it)
                }
        } finally {
            pool.shutdownNow()
            assertTrue(
                pool.awaitTermination(TIMEOUT, TimeUnit.SECONDS),
                "Executor pool didn't shutdown under timeout"
            )
        }
    }

    private fun <T : Any> inNewTransaction(block: () -> T): T {
        return transactionTemplate.execute {
            block()
        }
    }

    private fun persistProjectContext(): ProjectContext = inNewTransaction {
        val fixtures = fixtures(entityManager)
        val owner = fixtures.principal()
        val project = fixtures.project(ownerPrincipal = owner)
        fixtures.grantSystemPermission(
            principal = owner,
            scope = project.scope,
            SystemPermissionNames.PROJECT_READ.key,
            SystemPermissionNames.PROJECT_SERVICES_MANAGE.key
        )

        ProjectContext(
            owner = owner,
            project = project
        )
    }

    private data class ProjectContext(
        val owner: Principal,
        val project: Project
    )

    private fun fixtures(entityManager: EntityManager) = IamProjectFixtures(entityManager)

    @TestConfiguration
    class LatchClockConfiguration {
        @Bean
        @Primary
        fun latchClock(): LatchClock = LatchClock(
            Instant.parse("2026-01-01T00:00:00Z")
        )
    }
}

class LatchClock(
    private val current: Instant
) : Clock() {
    private val reached = CountDownLatch(1)
    private val release = CountDownLatch(1)

    private val currentThread = ThreadLocal.withInitial { false }

    override fun getZone(): ZoneId {
        return ZoneOffset.UTC
    }

    override fun withZone(zone: ZoneId?): Clock {
        return fixed(current, zone)
    }

    override fun instant(): Instant {
        if (!currentThread.get()) {
            return current
        }

        reached.countDown()
        assertTrue(
            release.await(TIMEOUT, TimeUnit.SECONDS),
            "Clock.instant() wasn't released"
        )
        return current
    }

    fun <T> coordinate(block: () -> T): T {
        currentThread.set(true)
        return try {
            block()
        } finally {
            currentThread.remove()
        }
    }

    fun waitForReached() {
        assertTrue(
            reached.await(TIMEOUT, TimeUnit.SECONDS),
            "Service create didn't reach Clock.instant()"
        )
    }

    fun release() {
        release.countDown()
    }
}
