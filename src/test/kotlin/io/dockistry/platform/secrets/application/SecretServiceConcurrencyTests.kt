package io.dockistry.platform.secrets.application

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.domain.SecretAlreadyExistsException
import io.dockistry.platform.secrets.domain.SecretWarningType
import io.dockistry.platform.secrets.infrastructure.ManagedServiceSecretReferenceRepository
import io.dockistry.platform.secrets.infrastructure.ProjectSecretDelegationRepository
import io.dockistry.platform.secrets.infrastructure.ServiceSecretWarningRepository
import io.dockistry.platform.secrets.infrastructure.SecretRepository
import io.dockistry.platform.services.application.ManagedServiceService
import io.dockistry.platform.services.application.dto.ManagedServiceCreateCommand
import io.dockistry.platform.services.domain.ManagedServiceConfigurationDraft
import io.dockistry.platform.services.domain.ManagedServiceDesiredState
import io.dockistry.platform.services.domain.ManagedServiceSecretReference
import io.dockistry.platform.testsupport.IamProjectFixtures
import io.dockistry.platform.testsupport.MutableClock
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Testcontainers
@Import(
    TestcontainersConfig::class,
    SecretServiceConcurrencyTests.ConcurrencySecretTestConfiguration::class,
    TestClockConfig::class
)
class SecretServiceConcurrencyTests(
    @Autowired private val clock: MutableClock,
    @Autowired private val secretService: SecretService,
    @Autowired private val managedServiceService: ManagedServiceService,
    @Autowired private val serviceSecretWarningRepository: ServiceSecretWarningRepository,
    @Autowired private val transactionManager: PlatformTransactionManager
) {
    companion object {
        const val TIMEOUT = 15L
        const val THREADS = 2
    }

    private val transactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @Autowired
    private lateinit var referenceRepository: LatchingManagedServiceSecretReferenceRepository

    @Autowired
    private lateinit var delegationRepository: LatchingProjectSecretDelegationRepository

    @Autowired
    private lateinit var secretRepository: LatchingSecretRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `concurrent revoke after configuration create creates delegation_revoked warnings`() {
        val testData = prepareTestData()
        val (project, owner, secretId, secretKey, secretVersion) = testData

        runConcurrencyTest(testData) {
            managedServiceService.create(
                ManagedServiceCreateCommand(
                    projectId = project.id,
                    principal = owner,
                    name = "owner-project-service" + suffix(),
                    configuration = configurationDraft(
                        secretId = secretId,
                        secretKey = secretKey,
                        secretVersion = secretVersion
                    )
                )
            )
        }
    }

    @Test
    fun `concurrent create with matching payload returns one logical secret`() {
        val owner = transactionTemplate.execute {
            IamProjectFixtures(entityManager).principal()
        }
        val name = "matching-${suffix()}"
        val barrier = secretRepository.armSave()
        val executor = Executors.newFixedThreadPool(THREADS)

        try {
            val creates = List(THREADS) {
                executor.submit<String> {
                    secretService.create(name, null, mapOf("password" to "same"), owner)
                }
            }

            barrier.awaitAll()
            barrier.release()
            val ids = creates.map { it.get(TIMEOUT, TimeUnit.SECONDS) }

            assertEquals(1, ids.toSet().size)
            val stored = transactionTemplate.execute {
                secretRepository.findByOwnerAndName(owner, name)
            }
            assertNotNull(stored)
            assertEquals(ids.first(), stored.id)
            assertEquals(1, stored.versions.size)
        } finally {
            barrier.release()
            secretRepository.disarmSave()
            closeExecutor(executor)
        }
    }

    @Test
    fun `concurrent create with different payload returns one conflict`() {
        val owner = transactionTemplate.execute {
            IamProjectFixtures(entityManager).principal()
        }
        val name = "conflicting-${suffix()}"
        val barrier = secretRepository.armSave()
        val executor = Executors.newFixedThreadPool(THREADS)

        try {
            val creates = listOf("first", "second").map { value ->
                executor.submit<String> {
                    secretService.create(name, null, mapOf("password" to value), owner)
                }
            }

            barrier.awaitAll()
            barrier.release()
            val outcomes = creates.map(::outcome)

            assertEquals(1, outcomes.filterIsInstance<String>().size)
            val conflict = assertIs<SecretAlreadyExistsException>(
                outcomes.filterIsInstance<Throwable>().single()
            )
            assertEquals("secret_already_exists", conflict.code)
            val stored = transactionTemplate.execute {
                secretRepository.findByOwnerAndName(owner, name)
            }
            assertNotNull(stored)
            assertEquals(outcomes.filterIsInstance<String>().single(), stored.id)
            assertEquals(1, stored.versions.size)
        } finally {
            barrier.release()
            secretRepository.disarmSave()
            closeExecutor(executor)
        }
    }

    @Test
    fun `concurrent delegate returns one persisted delegation`() {
        val (owner, projectId, secretId) = transactionTemplate.execute {
            val fixtures = IamProjectFixtures(entityManager)
            val owner = fixtures.principal()
            val project = fixtures.project(ownerPrincipal = owner)
            fixtures.grantSystemPermission(
                owner, project.scope,
                SystemPermissionNames.PROJECT_READ.key,
                SystemPermissionNames.SECRETS_DELEGATE.key
            )
            val secretId = secretService.create(
                "delegate-${suffix()}", null, mapOf("password" to "value"), owner
            )
            Triple(owner, project.id, secretId)
        }
        val barrier = delegationRepository.armSave()
        val executor = Executors.newFixedThreadPool(THREADS)

        try {
            val delegations = List(THREADS) {
                executor.submit<UUID> {
                    secretService.delegate(secretId, projectId, owner)
                }
            }

            barrier.awaitAll()
            barrier.release()
            val ids = delegations.map { it.get(TIMEOUT, TimeUnit.SECONDS) }

            assertEquals(1, ids.toSet().size)
            val stored = transactionTemplate.execute {
                delegationRepository.findAll().filter {
                    it.project.id == projectId && it.secret.id == secretId
                }
            }
            assertEquals(1, stored.size)
            assertEquals(ids.first(), stored.single().id)
        } finally {
            barrier.release()
            delegationRepository.disarmSave()
            closeExecutor(executor)
        }
    }

    private fun outcome(future: Future<String>): Any = try {
        future.get(TIMEOUT, TimeUnit.SECONDS)
    } catch (exception: ExecutionException) {
        exception.cause ?: throw exception
    }

    private fun closeExecutor(executor: ExecutorService) {
        executor.shutdown()
        assertTrue(
            executor.awaitTermination(TIMEOUT, TimeUnit.SECONDS),
            "Thread executor didn't terminate"
        )
    }

    private fun runConcurrencyTest(testData: ConcurrencyTestData, configurationBlock: () -> Any?) {
        val (project, owner, secretId, secretKey, secretVersion) = testData
        val executor = Executors.newFixedThreadPool(THREADS)
        try {
            val configurationTask = executor.submit {
                configurationBlock()
            }

            assertTrue(
                referenceRepository.flushEnteredLatch.await(
                    TIMEOUT,
                    TimeUnit.SECONDS
                ),
                "Latching reference repo's flush() wasn't entered"
            )

            val revokeTask = executor.submit {
                secretService.revoke(
                    secretId = secretId,
                    projectId = project.id,
                    principal = owner
                )
            }

            assertTrue(
                delegationRepository.delegationLockAttemptLatch.await(
                    TIMEOUT,
                    TimeUnit.SECONDS
                ),
                "Both delegation lock attempts weren't reached"
            )

            assertNotNull(delegationRepository.secondPid)

            assertTrue(awaitTrue({
                val blockingPids = transactionTemplate.execute {
                    entityManager.createNativeQuery(
                        "SELECT UNNEST(pg_blocking_pids(:blockedPid))"
                    )
                        .setParameter("blockedPid", delegationRepository.secondPid)
                        .resultList
                        .map { it as Int }
                }
                delegationRepository.firstPid in blockingPids
            }, TIMEOUT), "Revoke wasn't blocked by the configuration transaction")

            referenceRepository.flushExitLatch.countDown()

            configurationTask.get(TIMEOUT, TimeUnit.SECONDS)
            revokeTask.get(TIMEOUT, TimeUnit.SECONDS)

            assertNull(delegationRepository.getByProjectAndSecret(project.id, secretId))
            assertTrue {
                transactionTemplate.execute {
                    val warnings = serviceSecretWarningRepository.findAll()
                    warnings.size == 1 &&
                            warnings.all { warning ->
                                warning.service.project.id == project.id &&
                                        warning.secretId == secretId &&
                                        warning.secretKey == secretKey &&
                                        warning.warningType == SecretWarningType.DELEGATION_REVOKED
                            }
                }
            }
        } finally {
            referenceRepository.flushExitLatch.countDown()
            closeExecutor(executor)
        }
    }

    private fun prepareTestData(): ConcurrencyTestData {
        val fixtures = IamProjectFixtures(entityManager)

        val secretKey = "owner-secret-key" + suffix()

        val (project, owner, secretId) = transactionTemplate.execute {
            val owner = fixtures.principal()
            val project = fixtures.project(
                name = "concurrent-" + suffix(),
                slug = "concurrent-" + suffix(),
                ownerPrincipal = owner,
                createdAt = clock.instant()
            )

            fixtures.grantSystemPermission(
                owner, project.scope,
                SystemPermissionNames.PROJECT_READ.key,
                SystemPermissionNames.PROJECT_SERVICES_MANAGE.key,
                SystemPermissionNames.SECRETS_USE.key,
                SystemPermissionNames.SECRETS_DELEGATE.key
            )

            val secretId = secretService.create(
                name = "owner-secret" + suffix(),
                description = null,
                values = mapOf(secretKey to "very-secret-value"),
                ownerPrincipal = owner
            )

            DelegateTestData(project, owner, secretId)
        }

        secretService.delegate(
            secretId = secretId,
            projectId = project.id,
            ownerPrincipal = owner
        )

        return ConcurrencyTestData(
            project = project,
            owner = owner,
            secretId = secretId,
            secretKey = secretKey
        )
    }

    private fun configurationDraft(
        secretId: String,
        secretKey: String,
        secretVersion: String?
    ) =
        ManagedServiceConfigurationDraft(
            imageRef = "nginx:latest",
            command = listOf(),
            args = listOf(),
            env = mapOf(),
            secretReferences = listOf(
                ManagedServiceSecretReference(
                    secretId = secretId,
                    key = secretKey,
                    envName = "SECRET_VALUE",
                    secretVersion = secretVersion
                )
            ),
            endpoints = listOf(),
            volumes = listOf(),
            restartPolicy = DockerRestartPolicyType.UNLESS_STOPPED,
            restartMaxRetries = null,
            memoryLimitBytes = null,
            cpuLimitNanoCpus = null,
            desiredState = ManagedServiceDesiredState.RUNNING
        )

    @TestConfiguration
    private class ConcurrencySecretTestConfiguration {
        @Bean
        @Primary
        fun latchingSecretRepository(
            secretRepository: SecretRepository
        ): LatchingSecretRepository {
            return LatchingSecretRepository(secretRepository)
        }

        @Bean
        @Primary
        fun latchingReferenceRepository(
            referenceRepository: ManagedServiceSecretReferenceRepository
        ): LatchingManagedServiceSecretReferenceRepository {
            return LatchingManagedServiceSecretReferenceRepository(referenceRepository)
        }

        @Bean
        @Primary
        fun latchingDelegationRepository(
            delegationRepository: ProjectSecretDelegationRepository,
            entityManager: EntityManager
        ): LatchingProjectSecretDelegationRepository {
            return LatchingProjectSecretDelegationRepository(
                delegationRepository,
                entityManager
            )
        }
    }

    @Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
    private class LatchingManagedServiceSecretReferenceRepository(
        val delegate: ManagedServiceSecretReferenceRepository
    ) : ManagedServiceSecretReferenceRepository by delegate {
        val flushEnteredLatch = CountDownLatch(1)
        val flushExitLatch = CountDownLatch(1)

        override fun flush() {
            delegate.flush()
            flushEnteredLatch.countDown()
            assertTrue(
                flushExitLatch.await(TIMEOUT, TimeUnit.SECONDS),
                "Latch for exiting flush() wasn't counted down"
            )
        }
    }

    private class LatchingProjectSecretDelegationRepository(
        val delegate: ProjectSecretDelegationRepository,
        val entityManager: EntityManager
    ) : ProjectSecretDelegationRepository by delegate {
        val delegationLockAttemptLatch = CountDownLatch(2)

        @Volatile
        private var saveBarrier: SaveBarrier? = null

        fun armSave(): SaveBarrier = SaveBarrier().also { saveBarrier = it }

        fun disarmSave() {
            saveBarrier = null
        }

        override fun <S : ProjectSecretDelegation> saveAndFlush(entity: S): S {
            saveBarrier?.awaitRelease()
            return delegate.saveAndFlush(entity)
        }

        var firstPid: Int? = null
        var secondPid: Int? = null

        override fun findByProjectAndSecretForNoKeyUpdateBatch(
            projectId: UUID,
            secretIds: List<String>
        ): List<ProjectSecretDelegation> {
            if (delegationLockAttemptLatch.count == 2L) {
                firstPid = getBackendPid()
            } else if (delegationLockAttemptLatch.count == 1L) {
                secondPid = getBackendPid()
            }

            delegationLockAttemptLatch.countDown()

            return delegate.findByProjectAndSecretForNoKeyUpdateBatch(
                projectId = projectId,
                secretIds = secretIds
            )
        }

        private fun getBackendPid(): Int {
            return entityManager.createNativeQuery("SELECT PG_BACKEND_PID()").singleResult as Int
        }
    }

    private class LatchingSecretRepository(
        val delegate: SecretRepository
    ) : SecretRepository by delegate {
        @Volatile
        private var saveBarrier: SaveBarrier? = null

        fun armSave(): SaveBarrier = SaveBarrier().also { saveBarrier = it }

        fun disarmSave() {
            saveBarrier = null
        }

        override fun <S : Secret> saveAndFlush(entity: S): S {
            saveBarrier?.awaitRelease()
            return delegate.saveAndFlush(entity)
        }
    }

    private class SaveBarrier {
        private val entered = CountDownLatch(THREADS)
        private val release = CountDownLatch(1)

        fun awaitAll() {
            assertTrue(
                entered.await(TIMEOUT, TimeUnit.SECONDS),
                "Both saveAndFlush calls weren't entered"
            )
        }

        fun release() {
            release.countDown()
        }

        fun awaitRelease() {
            entered.countDown()
            assertTrue(
                release.await(TIMEOUT, TimeUnit.SECONDS),
                "saveAndFlush wasn't released"
            )
        }
    }

    fun suffix() = UUID.randomUUID().toString().take(8)

    private fun awaitTrue(predicate: () -> Boolean, secondsTimeout: Long): Boolean {
        val deadline = System.currentTimeMillis() + secondsTimeout * 1_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) {
                return true
            }
            Thread.sleep(100)
        }
        return false
    }

    private data class DelegateTestData(
        val project: Project,
        val owner: Principal,
        val secretId: String,
    )

    private data class ConcurrencyTestData(
        val project: Project,
        val owner: Principal,
        val secretId: String,
        val secretKey: String,
        val secretVersion: String? = null
    )
}
