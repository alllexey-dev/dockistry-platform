package io.dockistry.platform.secrets.application

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.secrets.domain.SecretAlreadyExistsException
import io.dockistry.platform.secrets.domain.SecretNotFoundException
import io.dockistry.platform.secrets.domain.SecretWarningType
import io.dockistry.platform.secrets.infrastructure.ManagedServiceSecretReferenceRepository
import io.dockistry.platform.secrets.infrastructure.ProjectSecretDelegationRepository
import io.dockistry.platform.secrets.infrastructure.SecretRepository
import io.dockistry.platform.secrets.infrastructure.SecretVersionRepository
import io.dockistry.platform.secrets.infrastructure.ServiceSecretWarningRepository
import io.dockistry.platform.services.application.ManagedServiceService
import io.dockistry.platform.services.application.dto.ManagedServiceCreateCommand
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceConfigurationDraft
import io.dockistry.platform.services.domain.ManagedServiceDesiredState
import io.dockistry.platform.services.domain.ManagedServiceSecretReference
import io.dockistry.platform.testsupport.IamProjectFixtures
import io.dockistry.platform.testsupport.MutableClock
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.AFTER_REFERENCE_FLUSH
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_DELEGATION_ROW_LOCK
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_DELEGATION_SAVE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_PRINCIPAL_LOCK
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_SECRET_DELEGATIONS_LOCK
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_SECRET_ROW_LOCK
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_SECRET_SAVE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyTestSupport
import io.dockistry.platform.testsupport.concurrency.RepositoryConcurrencyTestConfiguration
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
@Testcontainers
@Import(
    TestcontainersConfig::class,
    RepositoryConcurrencyTestConfiguration::class,
    TestClockConfig::class
)
class SecretServiceConcurrencyTests @Autowired constructor(
    private val clock: MutableClock,
    private val secretService: SecretService,
    private val managedServiceService: ManagedServiceService,
    private val serviceSecretWarningRepository: ServiceSecretWarningRepository,
    private val concurrency: ConcurrencyTestSupport
) {
    @Autowired
    private lateinit var delegationRepository: ProjectSecretDelegationRepository

    @Autowired
    private lateinit var secretRepository: SecretRepository

    @Autowired
    private lateinit var versionRepository: SecretVersionRepository

    @Autowired
    private lateinit var referenceRepository: ManagedServiceSecretReferenceRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `concurrent revoke after configuration create creates delegation_revoked warnings`() {
        val (project, owner, secretId, secretKey, secretVersion) = prepareTestData()
        concurrency.run {
            val afterFlush = pauseAt(AFTER_REFERENCE_FLUSH)
            val configuration = start("create configuration") {
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
            afterFlush.awaitReached()
            val delegationLock = observeNext(BEFORE_DELEGATION_ROW_LOCK)
            val revoke = start("revoke delegation") {
                secretService.revoke(secretId, project.id, owner)
            }
            assertBlocked(delegationLock, by = afterFlush)
            afterFlush.release()
            configuration.await()
            revoke.await()
        }

        assertNull(delegationRepository.getByProjectAndSecret(project.id, secretId))
        concurrency.inNewTransaction {
            val warnings = serviceSecretWarningRepository.findAll().filter {
                it.service.project.id == project.id
            }
            assertEquals(1, warnings.size)
            val warning = warnings.single()
            assertEquals(secretId, warning.secretId)
            assertEquals(secretKey, warning.secretKey)
            assertEquals(SecretWarningType.DELEGATION_REVOKED, warning.warningType)
        }
    }

    @Test
    fun `concurrent create with matching payload returns one logical secret`() {
        val owner = concurrency.inNewTransaction {
            IamProjectFixtures(entityManager).principal()
        }
        val name = "matching-${suffix()}"
        val ids = concurrency.run {
            val beforeSave = pauseAt(BEFORE_SECRET_SAVE, matching = name, participants = 2)
            val creates = List(2) {
                start("create matching secret") {
                    secretService.create(name, null, mapOf("password" to "same"), owner)
                }
            }
            beforeSave.awaitReached()
            beforeSave.release()
            creates.map { it.await() }
        }

        assertEquals(1, ids.toSet().size)
        concurrency.inNewTransaction {
            val stored = assertNotNull(secretRepository.findByOwnerAndName(owner, name))
            assertEquals(ids.first(), stored.id)
            assertEquals(1, stored.versions.size)
        }
    }

    @Test
    fun `concurrent create with different payload returns one conflict`() {
        val owner = concurrency.inNewTransaction {
            IamProjectFixtures(entityManager).principal()
        }
        val name = "conflicting-${suffix()}"
        val outcomes = concurrency.run {
            val beforeSave = pauseAt(BEFORE_SECRET_SAVE, matching = name, participants = 2)
            val creates = listOf("first", "second").map { value ->
                start("create secret with $value payload") {
                    secretService.create(name, null, mapOf("password" to value), owner)
                }
            }
            beforeSave.awaitReached()
            beforeSave.release()
            creates.map { it.outcome() }
        }

        val ids = outcomes.mapNotNull { it.getOrNull() }
        assertEquals(1, ids.size)
        val conflict = assertIs<SecretAlreadyExistsException>(
            outcomes.mapNotNull { it.exceptionOrNull() }.single()
        )
        assertEquals("secret_already_exists", conflict.code)
        concurrency.inNewTransaction {
            val stored = assertNotNull(secretRepository.findByOwnerAndName(owner, name))
            assertEquals(ids.single(), stored.id)
            assertEquals(1, stored.versions.size)
        }
    }

    @Test
    fun `concurrent delegate waits for principal lock and returns one delegation`() {
        val (owner, projectId, secretId) = concurrency.inNewTransaction {
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
        val ids = concurrency.run {
            val beforeSave = pauseAt(BEFORE_DELEGATION_SAVE, matching = secretId)
            val first = start("first delegation") {
                secretService.delegate(secretId, projectId, owner)
            }
            beforeSave.awaitReached()
            val principalLock = observeNext(BEFORE_PRINCIPAL_LOCK)
            val second = start("second delegation") {
                secretService.delegate(secretId, projectId, owner)
            }
            assertBlocked(principalLock, by = beforeSave)
            beforeSave.release()
            listOf(first.await(), second.await())
        }

        assertEquals(1, ids.toSet().size)
        concurrency.inNewTransaction {
            val stored = delegationRepository.findAll().filter {
                it.project.id == projectId && it.secret.id == secretId
            }
            assertEquals(1, stored.size)
            assertEquals(ids.first(), stored.single().id)
        }
    }

    @Test
    fun `rotate waits for latest version deletion and outdates the remaining latest reference`() {
        val data = prepareTestData()
        val service = createService(data, secretVersion = null)
        val deletedVersionId = latestVersionId(data.secretId)
        val rotatedVersionId = concurrency.run {
            val deletionLocks = pauseAt(BEFORE_SECRET_DELEGATIONS_LOCK, matching = data.secretId)
            val deletion = start("delete latest version") {
                secretService.deleteVersion(data.secretId, deletedVersionId, data.owner)
            }
            deletionLocks.awaitReached()
            val secretLock = observeNext(BEFORE_SECRET_ROW_LOCK)
            val rotation = start("rotate") {
                secretService.rotate(data.secretId, mapOf(data.secretKey to "rotated"), data.owner)
            }
            assertBlocked(secretLock, by = deletionLocks)
            deletionLocks.release()
            deletion.await()
            rotation.await()
        }

        assertEquals(rotatedVersionId, latestVersionId(data.secretId))
        assertFalse(versionRepository.existsById(deletedVersionId))
        assertEquals(
            setOf(SecretWarningType.DELETED, SecretWarningType.OUTDATED),
            activeWarnings(service).map { it.warningType }.toSet()
        )
    }

    @Test
    fun `version deletion waits for configuration and warns its new pinned reference`() {
        val data = prepareTestData()
        val versionId = latestVersionId(data.secretId)
        val service = concurrency.run {
            val afterFlush = pauseAt(AFTER_REFERENCE_FLUSH)
            val configuration = start("create pinned configuration") {
                createService(data, secretVersion = versionId)
            }
            afterFlush.awaitReached()
            val delegationsLock = observeNext(BEFORE_SECRET_DELEGATIONS_LOCK)
            val deletion = start("delete pinned version") {
                secretService.deleteVersion(data.secretId, versionId, data.owner)
            }
            assertBlocked(delegationsLock, by = afterFlush)
            afterFlush.release()
            deletion.await()
            configuration.await()
        }

        assertEquals(emptyList(), referenceRepository.findByServiceId(service.id))
        val warning = activeWarnings(service).single()
        assertEquals(SecretWarningType.DELETED, warning.warningType)
        assertEquals(versionId, warning.secretVersion)
    }

    @Test
    fun `secret deletion waits for configuration and warns its new reference`() {
        val data = prepareTestData()
        val service = concurrency.run {
            val afterFlush = pauseAt(AFTER_REFERENCE_FLUSH)
            val configuration = start("create latest configuration") {
                createService(data, secretVersion = null)
            }
            afterFlush.awaitReached()
            val delegationsLock = observeNext(BEFORE_SECRET_DELEGATIONS_LOCK)
            val deletion = start("delete secret") {
                secretService.delete(data.secretId, data.owner)
            }
            assertBlocked(delegationsLock, by = afterFlush)
            afterFlush.release()
            deletion.await()
            configuration.await()
        }

        assertFalse(secretRepository.existsById(data.secretId))
        assertEquals(emptyList(), referenceRepository.findByServiceId(service.id))
        val warning = activeWarnings(service).single()
        assertEquals(SecretWarningType.DELETED, warning.warningType)
        assertNull(warning.secretVersion)
    }

    @Test
    fun `delegation waits for secret deletion and then fails as missing`() {
        val data = prepareTestData()
        val otherProjectId = concurrency.inNewTransaction {
            val fixtures = IamProjectFixtures(entityManager)
            val project = fixtures.project(ownerPrincipal = data.owner)
            fixtures.grantSystemPermission(
                data.owner, project.scope,
                SystemPermissionNames.PROJECT_READ.key,
                SystemPermissionNames.SECRETS_DELEGATE.key
            )
            project.id
        }
        val error = concurrency.run {
            val deletionLocks = pauseAt(BEFORE_SECRET_DELEGATIONS_LOCK, matching = data.secretId)
            val deletion = start("delete secret") {
                secretService.delete(data.secretId, data.owner)
            }
            deletionLocks.awaitReached()
            val principalLock = observeNext(BEFORE_PRINCIPAL_LOCK)
            val delegation = start("delegate to another project") {
                secretService.delegate(data.secretId, otherProjectId, data.owner)
            }
            assertBlocked(principalLock, by = deletionLocks)
            deletionLocks.release()
            deletion.await()
            delegation.failure()
        }

        assertIs<SecretNotFoundException>(error)
        assertFalse(secretRepository.existsById(data.secretId))
        assertNull(delegationRepository.getByProjectAndSecret(otherProjectId, data.secretId))
    }

    private fun createService(data: ConcurrencyTestData, secretVersion: String?): ManagedService =
        managedServiceService.create(
            ManagedServiceCreateCommand(
                projectId = data.project.id,
                principal = data.owner,
                name = "service-" + suffix(),
                configuration = configurationDraft(
                    secretId = data.secretId,
                    secretKey = data.secretKey,
                    secretVersion = secretVersion
                )
            )
        )

    private fun latestVersionId(secretId: String): String =
        versionRepository.findLatestBySecretId(secretId).id

    private fun activeWarnings(service: ManagedService) =
        serviceSecretWarningRepository.findByServiceAndResolvedAtIsNullOrderByCreatedAtAsc(service)

    private fun prepareTestData(): ConcurrencyTestData {
        val fixtures = IamProjectFixtures(entityManager)

        val secretKey = "owner-secret-key" + suffix()

        val (project, owner, secretId) = concurrency.inNewTransaction {
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

    private fun suffix() = UUID.randomUUID().toString().take(8)

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
