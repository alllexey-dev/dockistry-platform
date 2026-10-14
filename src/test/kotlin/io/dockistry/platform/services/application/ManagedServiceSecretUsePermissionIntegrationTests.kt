package io.dockistry.platform.services.application

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.domain.SecretVersion
import io.dockistry.platform.secrets.domain.InvalidSecretReferenceException
import io.dockistry.platform.secrets.domain.SecretPermissionRequiredException
import io.dockistry.platform.secrets.domain.toDto
import io.dockistry.platform.secrets.infrastructure.ManagedServiceSecretReferenceRepository
import io.dockistry.platform.services.application.dto.ManagedServiceCreateCommand
import io.dockistry.platform.services.application.dto.ManagedServiceUpdateCommand
import io.dockistry.platform.services.domain.ManagedServiceSecretReference
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import io.dockistry.platform.services.testsupport.managedServiceDraft
import io.dockistry.platform.testsupport.IamProjectFixtures
import io.dockistry.platform.testsupport.MutableClock
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

@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
@Testcontainers
class ManagedServiceSecretUsePermissionIntegrationTests {
    @Autowired
    private lateinit var clock: MutableClock

    @Autowired
    private lateinit var createExecutor: ManagedServiceCreateExecutor

    @Autowired
    private lateinit var updateExecutor: ManagedServiceUpdateExecutor

    @Autowired
    private lateinit var serviceRepository: ManagedServiceRepository

    @Autowired
    private lateinit var referenceRepository: ManagedServiceSecretReferenceRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `create with secrets use permission persists referenced secret`() {
        val scenario = scenario()

        val service = createExecutor.execute(
            createCommand(scenario, scenario.permittedPrincipal, "allowed-create")
        )

        assertEquals(
            listOf(scenario.reference),
            referenceRepository.findByServiceId(service.id).map { it.toDto() }
        )
    }

    @Test
    fun `create without secrets use permission rejects referenced secret`() {
        val scenario = scenario()
        val command = createCommand(scenario, scenario.deniedPrincipal, "denied-create")

        val error = assertFailsWith<SecretPermissionRequiredException> {
            createExecutor.execute(command)
        }

        assertEquals("secret_permission_required", error.code)
        assertFalse(serviceRepository.existsByProjectIdAndName(command.projectId, command.name))
    }

    @Test
    fun `update with secrets use permission adds referenced secret`() {
        val scenario = scenario()
        val service = createExecutor.execute(
            createCommand(scenario, scenario.deniedPrincipal, "allowed-update", false)
        )
        clock.set(service.updatedAt.plusSeconds(1))

        val updated = updateExecutor.execute(
            updateCommand(scenario, service.id, scenario.permittedPrincipal, service.version)
        )

        assertEquals("redis", updated.imageRepository)
        assertEquals(
            listOf(scenario.reference),
            referenceRepository.findByServiceId(service.id).map { it.toDto() }
        )
    }

    @Test
    fun `update without secrets use permission cannot add referenced secret`() {
        val scenario = scenario()
        val service = createExecutor.execute(
            createCommand(scenario, scenario.deniedPrincipal, "denied-add", false)
        )

        val error = assertFailsWith<SecretPermissionRequiredException> {
            updateExecutor.execute(
                updateCommand(scenario, service.id, scenario.deniedPrincipal, service.version)
            )
        }

        assertEquals("secret_permission_required", error.code)
        val persisted = serviceRepository.findById(service.id).orElseThrow()
        assertEquals("nginx", persisted.imageRepository)
        assertEquals(service.version, persisted.version)
        assertEquals(emptyList(), referenceRepository.findByServiceId(service.id))
    }

    @Test
    fun `update without secrets use permission cannot remove referenced secret`() {
        val scenario = scenario()
        val service = createExecutor.execute(
            createCommand(scenario, scenario.permittedPrincipal, "denied-update")
        )

        val error = assertFailsWith<SecretPermissionRequiredException> {
            updateExecutor.execute(
                updateCommand(
                    scenario,
                    service.id,
                    scenario.deniedPrincipal,
                    service.version,
                    includeReference = false
                )
            )
        }

        assertEquals("secret_permission_required", error.code)
        val persisted = serviceRepository.findById(service.id).orElseThrow()
        assertEquals("nginx", persisted.imageRepository)
        assertEquals(service.version, persisted.version)
        assertEquals(
            listOf(scenario.reference),
            referenceRepository.findByServiceId(service.id).map { it.toDto() }
        )
    }

    @Test
    fun `update replaces and removes references with secrets use permission`() {
        val scenario = scenario()
        val service = createExecutor.execute(
            createCommand(scenario, scenario.permittedPrincipal, "replace-references")
        )
        val originalVersion = service.version
        val replacement = scenario.reference.copy(envName = "NEW_PASSWORD")
        clock.set(service.updatedAt.plusSeconds(1))

        val replaced = updateExecutor.execute(
            updateCommand(
                scenario, service.id, scenario.permittedPrincipal, originalVersion
            ).copy(
                configuration = managedServiceDraft().copy(
                    secretReferences = listOf(replacement)
                )
            )
        )

        assertEquals(originalVersion + 1, replaced.version)
        val replacedVersion = replaced.version
        assertEquals(
            listOf(replacement),
            referenceRepository.findByServiceId(service.id).map { it.toDto() }
        )

        clock.set(replaced.updatedAt.plusSeconds(1))
        val removed = updateExecutor.execute(
            updateCommand(
                scenario, service.id, scenario.permittedPrincipal, replacedVersion,
                includeReference = false
            ).copy(configuration = managedServiceDraft())
        )

        assertEquals(replacedVersion + 1, removed.version)
        assertEquals(emptyList(), referenceRepository.findByServiceId(service.id))
    }

    @Test
    fun `invalid replacement rolls back existing references and service version`() {
        val scenario = scenario()
        val service = createExecutor.execute(
            createCommand(scenario, scenario.permittedPrincipal, "rollback-references")
        )
        val invalid = scenario.reference.copy(key = "missing", envName = "NEW_PASSWORD")

        assertFailsWith<InvalidSecretReferenceException> {
            updateExecutor.execute(
                updateCommand(
                    scenario, service.id, scenario.permittedPrincipal, service.version
                ).copy(
                    configuration = managedServiceDraft().copy(
                        secretReferences = listOf(invalid)
                    )
                )
            )
        }

        assertEquals(service.version, serviceRepository.findById(service.id).orElseThrow().version)
        assertEquals(
            listOf(scenario.reference),
            referenceRepository.findByServiceId(service.id).map { it.toDto() }
        )
    }

    @Test
    fun `stale idempotent update still requires secrets use permission`() {
        val scenario = scenario()
        val service = createExecutor.execute(
            createCommand(scenario, scenario.permittedPrincipal, "stale-reference")
        )
        val command = updateCommand(
            scenario, service.id, scenario.deniedPrincipal, service.version + 1
        ).copy(configuration = managedServiceDraft().copy(
            secretReferences = listOf(scenario.reference)
        ))

        val error = assertFailsWith<SecretPermissionRequiredException> {
            updateExecutor.execute(command)
        }

        assertEquals("secret_permission_required", error.code)
        assertEquals(service.version, serviceRepository.findById(service.id).orElseThrow().version)
    }

    @Test
    fun `optimistic conflict resolution still requires secrets use permission`() {
        val scenario = scenario()
        val service = createExecutor.execute(
            createCommand(scenario, scenario.permittedPrincipal, "conflict-reference")
        )
        val command = updateCommand(
            scenario, service.id, scenario.deniedPrincipal, service.version
        ).copy(configuration = managedServiceDraft().copy(
            secretReferences = listOf(scenario.reference)
        ))

        val error = assertFailsWith<SecretPermissionRequiredException> {
            updateExecutor.resolveConflict(command)
        }

        assertEquals("secret_permission_required", error.code)
    }

    private fun scenario(): Scenario = transactionTemplate.execute {
        val fixtures = IamProjectFixtures(entityManager)
        val permittedPrincipal = fixtures.principal()
        val deniedPrincipal = fixtures.principal()
        val project = fixtures.project(ownerPrincipal = permittedPrincipal)

        fixtures.grantSystemPermission(
            permittedPrincipal,
            project.scope,
            SystemPermissionNames.PROJECT_READ.key,
            SystemPermissionNames.PROJECT_SERVICES_MANAGE.key,
            SystemPermissionNames.SECRETS_USE.key
        )
        fixtures.grantSystemPermission(
            deniedPrincipal,
            project.scope,
            SystemPermissionNames.PROJECT_READ.key,
            SystemPermissionNames.PROJECT_SERVICES_MANAGE.key
        )

        val now = Instant.parse("2026-01-01T00:00:00Z")
        val secret = Secret(
            name = "secret-${fixtures.unique()}",
            ownerPrincipal = permittedPrincipal,
            createdAt = now,
            updatedAt = now
        )
        entityManager.persist(secret)
        entityManager.persist(SecretVersion(secret, mapOf("password" to "encrypted"), now))
        entityManager.persist(ProjectSecretDelegation(secret, project, now, now))
        entityManager.flush()

        Scenario(
            project.id,
            permittedPrincipal,
            deniedPrincipal,
            ManagedServiceSecretReference(secret.id, "password", "PASSWORD")
        )
    }

    private fun createCommand(
        scenario: Scenario,
        principal: Principal,
        name: String,
        includeReference: Boolean = true
    ) = ManagedServiceCreateCommand(
        projectId = scenario.projectId,
        principal = principal,
        name = name,
        configuration = managedServiceDraft().copy(
            secretReferences = if (includeReference) listOf(scenario.reference) else emptyList()
        )
    )

    private fun updateCommand(
        scenario: Scenario,
        serviceId: UUID,
        principal: Principal,
        expectedVersion: Long,
        includeReference: Boolean = true
    ) = ManagedServiceUpdateCommand(
        projectId = scenario.projectId,
        serviceId = serviceId,
        principal = principal,
        expectedVersion = expectedVersion,
        configuration = managedServiceDraft(imageRef = "redis:7").copy(
            secretReferences = if (includeReference) listOf(scenario.reference) else emptyList()
        )
    )

    private data class Scenario(
        val projectId: UUID,
        val permittedPrincipal: Principal,
        val deniedPrincipal: Principal,
        val reference: ManagedServiceSecretReference
    )
}
