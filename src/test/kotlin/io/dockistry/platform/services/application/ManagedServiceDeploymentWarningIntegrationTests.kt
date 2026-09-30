package io.dockistry.platform.services.application

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.InsufficientPermissionException
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.secrets.domain.InvalidSecretReferenceException
import io.dockistry.platform.secrets.domain.ManagedServiceSecretReferenceEntity
import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.domain.SecretVersion
import io.dockistry.platform.secrets.domain.SecretWarningType
import io.dockistry.platform.secrets.domain.ServiceSecretWarning
import io.dockistry.platform.secrets.infrastructure.ServiceSecretWarningRepository
import io.dockistry.platform.services.domain.InvalidManagedServiceConfigException
import io.dockistry.platform.services.infrastructure.ManagedServiceDeploymentRepository
import io.dockistry.platform.services.testsupport.ManagedServiceFixtures
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
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
@Testcontainers
class ManagedServiceDeploymentWarningIntegrationTests {
    @Autowired private lateinit var lifecycleService: ManagedServiceDeploymentLifecycleService
    @Autowired private lateinit var warningRepository: ServiceSecretWarningRepository
    @Autowired private lateinit var deploymentRepository: ManagedServiceDeploymentRepository
    @Autowired private lateinit var transactionTemplate: TransactionTemplate
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun `begin resolves revoked delegation and outdated warnings for usable secrets`() {
        val scenario = scenario()

        val plan = lifecycleService.begin(scenario.serviceId, scenario.owner, false)

        assertEquals(scenario.serviceId, plan.serviceId)
        assertEquals(setOf("REVOKED", "OUTDATED"),
            plan.snapshot.secretReferences.map { it.envName }.toSet())
        assertEquals(setOf("REVOKED", "OUTDATED"),
            plan.secretPayloads.map { it.envName }.toSet())
        assertTrue(deploymentRepository.existsById(plan.deploymentId))
        scenario.warningIds.forEach { warningId ->
            assertNotNull(warningRepository.findById(warningId).orElseThrow().resolvedAt)
        }
    }

    @Test
    fun `begin without secrets use leaves both warnings active`() {
        val scenario = scenario()

        val error = assertFailsWith<InsufficientPermissionException> {
            lifecycleService.begin(
                scenario.serviceId,
                scenario.principalWithoutSecretsUse,
                false
            )
        }

        assertEquals("insufficient_permission_for_operation", error.code)
        assertWarningsStillActive(scenario)
    }

    @Test
    fun `begin without delegation leaves both warnings active`() {
        val scenario = scenario()
        transactionTemplate.execute {
            entityManager.createNativeQuery("""
                DELETE FROM project_secret_delegations
                WHERE project_id = :projectId AND secret_id = :secretId
            """).setParameter("projectId", scenario.projectId)
                .setParameter("secretId", scenario.revokedSecretId)
                .executeUpdate()
        }

        val error = assertFailsWith<InvalidSecretReferenceException> {
            lifecycleService.begin(scenario.serviceId, scenario.owner, false)
        }

        assertEquals("invalid_secret_reference", error.code)
        assertWarningsStillActive(scenario)
    }

    @Test
    fun `begin with invalid service configuration leaves both warnings active`() {
        val scenario = scenario()
        transactionTemplate.execute {
            entityManager.createNativeQuery("""
                UPDATE services SET env = '{"invalid-name":"value"}'::jsonb
                WHERE id = :serviceId
            """).setParameter("serviceId", scenario.serviceId).executeUpdate()
        }

        val error = assertFailsWith<InvalidManagedServiceConfigException> {
            lifecycleService.begin(scenario.serviceId, scenario.owner, false)
        }

        assertEquals("invalid_managed_service_config", error.code)
        assertWarningsStillActive(scenario)
    }

    @Test
    fun `begin with invalid secret reference leaves both warnings active`() {
        val scenario = scenario()
        transactionTemplate.execute {
            entityManager.createNativeQuery("""
                UPDATE managed_service_secret_references SET secret_key = 'missing'
                WHERE service_id = :serviceId AND env_name = 'OUTDATED'
            """).setParameter("serviceId", scenario.serviceId).executeUpdate()
        }

        val error = assertFailsWith<InvalidSecretReferenceException> {
            lifecycleService.begin(scenario.serviceId, scenario.owner, false)
        }

        assertEquals("invalid_secret_reference", error.code)
        assertWarningsStillActive(scenario)
    }

    private fun assertWarningsStillActive(scenario: Scenario) {
        scenario.warningIds.forEach { warningId ->
            assertNull(warningRepository.findById(warningId).orElseThrow().resolvedAt)
        }
        assertTrue(deploymentRepository.findActiveByServiceId(scenario.serviceId).isEmpty())
    }

    private fun scenario(): Scenario = transactionTemplate.execute {
        val iam = IamProjectFixtures(entityManager)
        val owner = iam.principal()
        val principalWithoutSecretsUse = iam.principal()
        val project = iam.project(ownerPrincipal = owner)
        iam.grantSystemPermission(
            owner, project.scope,
            SystemPermissionNames.PROJECT_READ.key,
            SystemPermissionNames.PROJECT_SERVICES_MANAGE.key,
            SystemPermissionNames.SECRETS_USE.key
        )
        iam.grantSystemPermission(
            principalWithoutSecretsUse, project.scope,
            SystemPermissionNames.PROJECT_READ.key,
            SystemPermissionNames.PROJECT_SERVICES_MANAGE.key
        )

        val now = Instant.parse("2026-01-01T00:00:00Z")
        val service = ManagedServiceFixtures(entityManager).service(project)
        val warningIds = mutableListOf<UUID>()
        val secrets = listOf(
            SecretFixture("REVOKED", SecretWarningType.DELEGATION_REVOKED),
            SecretFixture("OUTDATED", SecretWarningType.OUTDATED)
        ).map { fixture ->
            val secret = Secret("secret-${iam.unique()}", null, owner, now, now)
            entityManager.persist(secret)
            entityManager.persist(SecretVersion(secret, mapOf("password" to "encrypted"), now))
            entityManager.persist(ProjectSecretDelegation(secret, project, now, now))
            entityManager.persist(ManagedServiceSecretReferenceEntity(
                service = service,
                secret = secret,
                secretKey = "password",
                envName = fixture.envName
            ))
            val warning = ServiceSecretWarning(
                service = service,
                secretId = secret.id,
                secretKey = "password",
                secretVersion = null,
                warningType = fixture.warningType,
                envName = fixture.envName,
                createdAt = now
            )
            entityManager.persist(warning)
            warningIds.add(warning.id)
            secret
        }
        entityManager.flush()

        Scenario(
            owner = owner,
            principalWithoutSecretsUse = principalWithoutSecretsUse,
            projectId = project.id,
            serviceId = service.id,
            revokedSecretId = secrets.first().id,
            warningIds = warningIds
        )
    }

    private data class SecretFixture(
        val envName: String,
        val warningType: SecretWarningType
    )

    private data class Scenario(
        val owner: Principal,
        val principalWithoutSecretsUse: Principal,
        val projectId: UUID,
        val serviceId: UUID,
        val revokedSecretId: String,
        val warningIds: List<UUID>
    )
}
