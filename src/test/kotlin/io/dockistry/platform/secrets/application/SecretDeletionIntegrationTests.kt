package io.dockistry.platform.secrets.application

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.secrets.domain.ManagedServiceSecretReferenceEntity
import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.domain.SecretNotFoundException
import io.dockistry.platform.secrets.domain.SecretVersion
import io.dockistry.platform.secrets.domain.SecretVersionNotFoundException
import io.dockistry.platform.secrets.domain.SecretWarningType
import io.dockistry.platform.secrets.domain.ServiceSecretWarning
import io.dockistry.platform.secrets.infrastructure.ManagedServiceSecretReferenceRepository
import io.dockistry.platform.secrets.infrastructure.ProjectSecretDelegationRepository
import io.dockistry.platform.secrets.infrastructure.SecretRepository
import io.dockistry.platform.secrets.infrastructure.SecretVersionRepository
import io.dockistry.platform.secrets.infrastructure.ServiceSecretWarningRepository
import io.dockistry.platform.services.testsupport.managedService
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
@Testcontainers
class SecretDeletionIntegrationTests {
    @Autowired private lateinit var secretService: SecretService
    @Autowired private lateinit var secretRepository: SecretRepository
    @Autowired private lateinit var versionRepository: SecretVersionRepository
    @Autowired private lateinit var delegationRepository: ProjectSecretDelegationRepository
    @Autowired private lateinit var referenceRepository: ManagedServiceSecretReferenceRepository
    @Autowired private lateinit var warningRepository: ServiceSecretWarningRepository
    @Autowired private lateinit var transactionTemplate: TransactionTemplate
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun `deleting an older version warns and cascades only its pinned references`() {
        val scenario = scenario()

        secretService.deleteVersion(scenario.secretId, scenario.olderVersionId, scenario.owner)

        assertEquals(setOf(scenario.latestVersionId), versionIds(scenario))
        assertEquals(setOf("LATEST", "PINNED_LATEST", "OTHER_LATEST"), referenceEnvNames(scenario))
        val warning = warnings(scenario).single()
        assertEquals(SecretWarningType.DELETED, warning.warningType)
        assertEquals("PINNED_OLDER", warning.envName)
        assertEquals(scenario.olderVersionId, warning.secretVersion)
        assertNull(warning.resolvedAt)
    }

    @Test
    fun `deleting the latest version also warns every latest reference`() {
        val scenario = scenario()

        secretService.deleteVersion(scenario.secretId, scenario.latestVersionId, scenario.owner)

        assertEquals(setOf(scenario.olderVersionId), versionIds(scenario))
        assertEquals(
            scenario.olderVersionId,
            versionRepository.findLatestBySecretId(scenario.secretId).id
        )
        assertEquals(setOf("LATEST", "PINNED_OLDER", "OTHER_LATEST"), referenceEnvNames(scenario))
        val warnings = warnings(scenario)
        assertEquals(
            setOf("LATEST", "PINNED_LATEST", "OTHER_LATEST"),
            warnings.map { it.envName }.toSet()
        )
        assertEquals(3, warnings.size)
        assertEquals(setOf(SecretWarningType.DELETED), warnings.map { it.warningType }.toSet())
    }

    @Test
    fun `deleting every version keeps the logical secret and its latest references`() {
        val scenario = scenario()

        secretService.deleteVersion(scenario.secretId, scenario.latestVersionId, scenario.owner)
        secretService.deleteVersion(scenario.secretId, scenario.olderVersionId, scenario.owner)

        assertTrue(secretRepository.existsById(scenario.secretId))
        assertEquals(emptySet(), versionIds(scenario))
        assertEquals(setOf("LATEST", "OTHER_LATEST"), referenceEnvNames(scenario))
        assertEquals(
            setOf("LATEST", "PINNED_LATEST", "PINNED_OLDER", "OTHER_LATEST"),
            warnings(scenario).map { it.envName }.toSet()
        )
    }

    @Test
    fun `deleting a missing or foreign version fails without side effects`() {
        val scenario = scenario()
        val foreignVersionId = transactionTemplate.execute {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val name = "foreign-${IamProjectFixtures(entityManager).unique()}"
            val foreign = Secret(name, null, scenario.owner, now, now)
            foreign.addVersion(SecretVersion(foreign, mapOf("password" to "encrypted"), now))
            entityManager.persist(foreign)
            foreign.versions.single().id
        }

        listOf(foreignVersionId, "ver-missing00000").forEach { versionId ->
            val error = assertFailsWith<SecretVersionNotFoundException> {
                secretService.deleteVersion(scenario.secretId, versionId, scenario.owner)
            }
            assertEquals("secret_version_not_found", error.code)
        }
        assertTrue(versionRepository.existsById(foreignVersionId))
        assertNothingChanged(scenario)
    }

    @Test
    fun `non owner cannot delete a version or the logical secret`() {
        val scenario = scenario()

        assertFailsWith<SecretNotFoundException> {
            secretService.deleteVersion(
                scenario.secretId, scenario.latestVersionId, scenario.stranger
            )
        }
        assertFailsWith<SecretNotFoundException> {
            secretService.delete(scenario.secretId, scenario.stranger)
        }

        assertNothingChanged(scenario)
    }

    @Test
    fun `deleting the logical secret warns every reference and removes all its data`() {
        val scenario = scenario()

        secretService.delete(scenario.secretId, scenario.owner)

        assertFalse(secretRepository.existsById(scenario.secretId))
        assertEquals(emptySet(), versionIds(scenario))
        assertEquals(emptySet(), referenceEnvNames(scenario))
        scenario.projectIds.forEach { projectId ->
            assertNull(delegationRepository.getByProjectAndSecret(projectId, scenario.secretId))
        }
        val warnings = warnings(scenario)
        assertEquals(
            setOf("LATEST", "PINNED_LATEST", "PINNED_OLDER", "OTHER_LATEST"),
            warnings.map { it.envName }.toSet()
        )
        assertEquals(4, warnings.size)
        assertEquals(setOf(SecretWarningType.DELETED), warnings.map { it.warningType }.toSet())

        assertFailsWith<SecretNotFoundException> {
            secretService.delete(scenario.secretId, scenario.owner)
        }
    }

    private fun assertNothingChanged(scenario: Scenario) {
        assertEquals(setOf(scenario.olderVersionId, scenario.latestVersionId), versionIds(scenario))
        assertEquals(
            setOf("LATEST", "PINNED_LATEST", "PINNED_OLDER", "OTHER_LATEST"),
            referenceEnvNames(scenario)
        )
        assertEquals(emptyList(), warnings(scenario))
    }

    private fun versionIds(scenario: Scenario): Set<String> =
        versionRepository.findAll()
            .filter { it.secret.id == scenario.secretId }
            .map { it.id }
            .toSet()

    private fun referenceEnvNames(scenario: Scenario): Set<String> =
        referenceRepository.findByServiceIdIn(scenario.serviceIds).map { it.envName }.toSet()

    private fun warnings(scenario: Scenario): List<ServiceSecretWarning> =
        warningRepository.findAll().filter { it.service.id in scenario.serviceIds }

    /*
     * One Secret delegated to two projects: the main service references latest, the older
     * and the latest version; a service in the other project references latest only.
     */
    private fun scenario(): Scenario = transactionTemplate.execute {
        val fixtures = IamProjectFixtures(entityManager)
        val owner = fixtures.principal()
        val stranger = fixtures.principal()
        val project = fixtures.project(ownerPrincipal = owner)
        val otherProject = fixtures.project(ownerPrincipal = owner)
        val older = Instant.parse("2026-01-01T00:00:00Z")
        val latest = older.plusSeconds(60)
        val secret = Secret("secret-${fixtures.unique()}", null, owner, older, older)
        entityManager.persist(secret)
        val olderVersion = SecretVersion(secret, mapOf("password" to "old"), older)
        val latestVersion = SecretVersion(secret, mapOf("password" to "new"), latest)
        entityManager.persist(olderVersion)
        entityManager.persist(latestVersion)
        entityManager.persist(ProjectSecretDelegation(secret, project, older, older))
        entityManager.persist(ProjectSecretDelegation(secret, otherProject, older, older))
        val service = managedService(project, "service-${fixtures.unique()}")
        val otherService = managedService(otherProject, "service-${fixtures.unique()}")
        entityManager.persist(service)
        entityManager.persist(otherService)
        mapOf(
            "LATEST" to null,
            "PINNED_OLDER" to olderVersion.id,
            "PINNED_LATEST" to latestVersion.id
        ).forEach { (envName, versionId) ->
            entityManager.persist(ManagedServiceSecretReferenceEntity(
                service = service,
                secret = secret,
                secretKey = "password",
                secretVersion = versionId,
                envName = envName
            ))
        }
        entityManager.persist(ManagedServiceSecretReferenceEntity(
            service = otherService,
            secret = secret,
            secretKey = "password",
            secretVersion = null,
            envName = "OTHER_LATEST"
        ))
        entityManager.flush()
        Scenario(
            owner = owner,
            stranger = stranger,
            projectIds = listOf(project.id, otherProject.id),
            serviceIds = listOf(service.id, otherService.id),
            secretId = secret.id,
            olderVersionId = olderVersion.id,
            latestVersionId = latestVersion.id
        )
    }

    private data class Scenario(
        val owner: Principal,
        val stranger: Principal,
        val projectIds: List<UUID>,
        val serviceIds: List<UUID>,
        val secretId: String,
        val olderVersionId: String,
        val latestVersionId: String
    )
}
