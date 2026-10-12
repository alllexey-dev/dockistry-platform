package io.dockistry.platform.secrets.application

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.InsufficientPermissionException
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.projects.domain.ProjectNotFoundException
import io.dockistry.platform.secrets.domain.ManagedServiceSecretReferenceEntity
import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.domain.SecretNotFoundException
import io.dockistry.platform.secrets.domain.SecretVersion
import io.dockistry.platform.secrets.domain.SecretWarningType
import io.dockistry.platform.secrets.infrastructure.ManagedServiceSecretReferenceRepository
import io.dockistry.platform.secrets.infrastructure.ProjectSecretDelegationRepository
import io.dockistry.platform.secrets.infrastructure.SecretRepository
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
@Testcontainers
class SecretRevokeIntegrationTests {
    @Autowired private lateinit var secretService: SecretService
    @Autowired private lateinit var delegationRepository: ProjectSecretDelegationRepository
    @Autowired private lateinit var referenceRepository: ManagedServiceSecretReferenceRepository
    @Autowired private lateinit var warningRepository: ServiceSecretWarningRepository
    @Autowired private lateinit var secretRepository: SecretRepository
    @Autowired private lateinit var transactionTemplate: TransactionTemplate
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun `owner revoke is repeatable and preserves every reference with one warning each`() {
        val scenario = scenario()

        secretService.revoke(scenario.secretId, scenario.projectId, scenario.owner)
        secretService.revoke(scenario.secretId, scenario.projectId, scenario.owner)

        assertNull(delegationRepository.getByProjectAndSecret(
            scenario.projectId, scenario.secretId
        ))
        assertEquals(2, referenceRepository.findByServiceId(scenario.serviceId).size)
        val warnings = warningRepository.findAll().filter { it.service.id == scenario.serviceId }
        assertEquals(setOf("PASSWORD_A", "PASSWORD_B"), warnings.map { it.envName }.toSet())
        assertEquals(2, warnings.size)
        assertEquals(setOf(SecretWarningType.DELEGATION_REVOKED),
            warnings.map { it.warningType }.toSet())
        assertEquals(setOf(null), warnings.map { it.resolvedAt }.toSet())
    }

    @Test
    fun `non owner cannot revoke delegation or create warnings`() {
        val scenario = scenario()

        assertFailsWith<SecretNotFoundException> {
            secretService.revoke(scenario.secretId, scenario.projectId, scenario.stranger)
        }

        assertNotNull(delegationRepository.getByProjectAndSecret(
            scenario.projectId, scenario.secretId
        ))
        assertEquals(2, referenceRepository.findByServiceId(scenario.serviceId).size)
        assertEquals(emptyList(), warningRepository.findAll().filter {
            it.service.id == scenario.serviceId
        })
    }

    @Test
    fun `force revoke removes another owner's delegation with the same warnings`() {
        val scenario = scenario()

        secretService.forceRevoke(scenario.secretId, scenario.projectId, scenario.admin)
        secretService.forceRevoke(scenario.secretId, scenario.projectId, scenario.admin)

        assertNull(delegationRepository.getByProjectAndSecret(
            scenario.projectId, scenario.secretId
        ))
        assertEquals(2, referenceRepository.findByServiceId(scenario.serviceId).size)
        val warnings = warningRepository.findAll().filter { it.service.id == scenario.serviceId }
        assertEquals(setOf("PASSWORD_A", "PASSWORD_B"), warnings.map { it.envName }.toSet())
        assertEquals(2, warnings.size)
        assertEquals(setOf(SecretWarningType.DELEGATION_REVOKED),
            warnings.map { it.warningType }.toSet())
        assertNotNull(secretRepository.findById(scenario.secretId).orElse(null))
    }

    @Test
    fun `force revoke requires the force-revoke permission even from the owner`() {
        val scenario = scenario()

        assertFailsWith<InsufficientPermissionException> {
            secretService.forceRevoke(scenario.secretId, scenario.projectId, scenario.reader)
        }
        assertFailsWith<InsufficientPermissionException> {
            secretService.forceRevoke(scenario.secretId, scenario.projectId, scenario.owner)
        }
        assertFailsWith<ProjectNotFoundException> {
            secretService.forceRevoke(scenario.secretId, scenario.projectId, scenario.stranger)
        }

        assertNotNull(delegationRepository.getByProjectAndSecret(
            scenario.projectId, scenario.secretId
        ))
        assertEquals(emptyList(), warningRepository.findAll().filter {
            it.service.id == scenario.serviceId
        })
    }

    private fun scenario(): Scenario = transactionTemplate.execute {
        val fixtures = IamProjectFixtures(entityManager)
        val owner = fixtures.principal()
        val stranger = fixtures.principal()
        val admin = fixtures.principal()
        val reader = fixtures.principal()
        val project = fixtures.project(ownerPrincipal = owner)
        fixtures.grantSystemPermission(
            admin, project.scope,
            SystemPermissionNames.PROJECT_READ.key,
            SystemPermissionNames.SECRETS_FORCE_REVOKE.key
        )
        fixtures.grantSystemPermission(
            reader, project.scope, SystemPermissionNames.PROJECT_READ.key
        )
        fixtures.grantSystemPermission(
            owner, project.scope,
            SystemPermissionNames.PROJECT_READ.key,
            SystemPermissionNames.SECRETS_DELEGATE.key
        )
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val secret = Secret("secret-${fixtures.unique()}", null, owner, now, now)
        entityManager.persist(secret)
        entityManager.persist(SecretVersion(secret, mapOf("password" to "encrypted"), now))
        entityManager.persist(ProjectSecretDelegation(secret, project, now, now))
        val service = managedService(project, "service-${fixtures.unique()}")
        entityManager.persist(service)
        listOf("PASSWORD_A", "PASSWORD_B").forEach { envName ->
            entityManager.persist(ManagedServiceSecretReferenceEntity(
                service = service,
                secret = secret,
                secretKey = "password",
                secretVersion = null,
                envName = envName
            ))
        }
        entityManager.flush()
        Scenario(owner, stranger, admin, reader, project.id, service.id, secret.id)
    }

    private data class Scenario(
        val owner: Principal,
        val stranger: Principal,
        val admin: Principal,
        val reader: Principal,
        val projectId: UUID,
        val serviceId: UUID,
        val secretId: String
    )
}
