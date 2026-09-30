package io.dockistry.platform.services.application

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.docker.application.ContainerService
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.model.DockerContainerCreateCommand
import io.dockistry.platform.docker.domain.model.DockerNetworkRef
import io.dockistry.platform.docker.domain.model.DockerImageRef
import io.dockistry.platform.iam.domain.InsufficientPermissionException
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.secrets.application.SecretService
import io.dockistry.platform.secrets.domain.InvalidSecretReferenceException
import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.domain.SecretVersion
import io.dockistry.platform.secrets.domain.SecretWarningType
import io.dockistry.platform.secrets.domain.SecretWarningsConfirmationRequiredException
import io.dockistry.platform.secrets.domain.ServiceSecretWarning
import io.dockistry.platform.secrets.infrastructure.ServiceSecretWarningRepository
import io.dockistry.platform.services.application.dto.ManagedServiceCreateCommand
import io.dockistry.platform.services.domain.ManagedServiceSecretReference
import io.dockistry.platform.services.infrastructure.ManagedServiceDeploymentRepository
import io.dockistry.platform.services.testsupport.managedServiceDraft
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(
    TestcontainersConfig::class,
    TestClockConfig::class,
    ManagedServiceSecretDeployIntegrationTests.MockContainerConfig::class
)
@Testcontainers
class ManagedServiceSecretDeployIntegrationTests {
    @Autowired private lateinit var service: ManagedServiceService
    @Autowired private lateinit var secretService: SecretService
    @Autowired private lateinit var containerService: ContainerService
    @Autowired private lateinit var deploymentRepository: ManagedServiceDeploymentRepository
    @Autowired private lateinit var warningRepository: ServiceSecretWarningRepository
    @Autowired private lateinit var transactionTemplate: TransactionTemplate
    @Autowired private lateinit var entityManager: EntityManager

    @BeforeEach
    fun resetContainer() {
        Mockito.reset(containerService)
    }

    @Test
    fun `deploy resolves latest and pinned versions into Docker command and keeps snapshot structured`() {
        val scenario = scenario(includePinned = true)
        stubNetwork(scenario)
        Mockito.doReturn("fake-container")
            .`when`(containerService)
            .createContainer(
                Mockito.any(DockerContainerCreateCommand::class.java)
                    ?: placeholderCommand()
            )

        val containerId = service.deploy(
            scenario.projectId, scenario.serviceId, scenario.owner, false
        )

        assertEquals("fake-container", containerId)
        val commandCaptor = ArgumentCaptor.forClass(DockerContainerCreateCommand::class.java)
        Mockito.verify(containerService).createContainer(
            commandCaptor.capture() ?: placeholderCommand()
        )
        val command = commandCaptor.value
        assertEquals(
            mapOf("PLAIN" to "literal", "LATEST" to "new-value", "PINNED" to "old-value"),
            command.env
        )
        assertEquals("nginx", command.image.repository)
        assertEquals("latest", command.image.tag)
        assertEquals("dockistry_${scenario.projectSlug}", command.networkName)
        Mockito.verify(containerService).startContainer("fake-container")

        val deployment = deploymentRepository.findAll().single {
            it.service.id == scenario.serviceId
        }
        assertEquals(
            scenario.references.toSet(),
            deployment.configSnapshot.secretReferences.toSet()
        )
        assertEquals(mapOf("PLAIN" to "literal"), deployment.configSnapshot.env)
    }

    @Test
    fun `revoked delegation blocks deploy before Docker calls`() {
        val scenario = scenario()
        secretService.revoke(scenario.secretId, scenario.projectId, scenario.owner)

        assertFailsWith<InvalidSecretReferenceException> {
            service.deploy(scenario.projectId, scenario.serviceId, scenario.owner, false)
        }

        Mockito.verifyNoInteractions(containerService)
        assertTrue(deploymentRepository.findAll().none { it.service.id == scenario.serviceId })
    }

    @Test
    fun `missing latest version blocks deploy before Docker calls`() {
        val scenario = scenario()
        transactionTemplate.execute {
            entityManager.createNativeQuery(
                "DELETE FROM secret_versions WHERE secret_id = :secretId"
            ).setParameter("secretId", scenario.secretId).executeUpdate()
        }

        assertFailsWith<InvalidSecretReferenceException> {
            service.deploy(scenario.projectId, scenario.serviceId, scenario.owner, false)
        }

        Mockito.verifyNoInteractions(containerService)
        assertTrue(deploymentRepository.findAll().none { it.service.id == scenario.serviceId })
    }

    @Test
    fun `deploy requires secrets use even when principal can manage service`() {
        val scenario = scenario()

        assertFailsWith<InsufficientPermissionException> {
            service.deploy(scenario.projectId, scenario.serviceId, scenario.manager, false)
        }

        Mockito.verifyNoInteractions(containerService)
        assertTrue(deploymentRepository.findAll().none { it.service.id == scenario.serviceId })
    }

    @Test
    fun `deleted warning needs confirmation and accepted warning stays resolved`() {
        val scenario = scenario()
        val warningId = transactionTemplate.execute {
            val managedService = entityManager.find(
                io.dockistry.platform.services.domain.ManagedService::class.java,
                scenario.serviceId
            )
            warningRepository.saveAndFlush(ServiceSecretWarning(
                service = managedService,
                secretId = scenario.secretId,
                secretKey = "password",
                secretVersion = null,
                warningType = SecretWarningType.DELETED,
                envName = "LATEST",
                createdAt = Instant.parse("2026-01-01T00:00:00Z")
            )).id
        }

        assertFailsWith<SecretWarningsConfirmationRequiredException> {
            service.deploy(scenario.projectId, scenario.serviceId, scenario.owner, false)
        }
        Mockito.verifyNoInteractions(containerService)
        assertTrue(deploymentRepository.findAll().none { it.service.id == scenario.serviceId })
        assertEquals(null, warningRepository.findById(warningId).orElseThrow().resolvedAt)

        stubNetwork(scenario)
        val stoppedAtDocker = FakeDockerPullException()
        Mockito.doThrow(stoppedAtDocker).`when`(containerService)
            .pullImage(DockerImageRef("nginx", "latest"))

        assertFailsWith<FakeDockerPullException> {
            service.deploy(scenario.projectId, scenario.serviceId, scenario.owner, true)
        }
        Mockito.verify(containerService).pullImage(DockerImageRef("nginx", "latest"))
        assertNotNull(warningRepository.findById(warningId).orElseThrow().resolvedAt)

        assertFailsWith<FakeDockerPullException> {
            service.deploy(scenario.projectId, scenario.serviceId, scenario.owner, false)
        }
        Mockito.verify(containerService, Mockito.times(2))
            .pullImage(DockerImageRef("nginx", "latest"))
    }

    private fun stubNetwork(scenario: Scenario) {
        val name = "dockistry_${scenario.projectSlug}"
        Mockito.doReturn(DockerNetworkRef(
            id = "fake-network",
            name = name,
            labels = DockerLabels.forProject(scenario.projectId)
        )).`when`(containerService).findNetworkByName(name)
    }

    private fun placeholderCommand() = DockerContainerCreateCommand(
        name = "placeholder",
        image = DockerImageRef("nginx", "latest"),
        networkName = "placeholder"
    )

    private fun scenario(includePinned: Boolean = false): Scenario {
        val setup = transactionTemplate.execute {
            val fixtures = IamProjectFixtures(entityManager)
            val owner = fixtures.principal()
            val manager = fixtures.principal()
            val project = fixtures.project(ownerPrincipal = owner)
            fixtures.grantSystemPermission(
                owner, project.scope,
                SystemPermissionNames.PROJECT_READ.key,
                SystemPermissionNames.PROJECT_SERVICES_MANAGE.key,
                SystemPermissionNames.SECRETS_USE.key
            )
            fixtures.grantSystemPermission(
                manager, project.scope,
                SystemPermissionNames.PROJECT_READ.key,
                SystemPermissionNames.PROJECT_SERVICES_MANAGE.key
            )
            val oldTime = Instant.parse("2025-01-01T00:00:00Z")
            val newTime = Instant.parse("2026-01-01T00:00:00Z")
            val secret = Secret("secret-${fixtures.unique()}", null, owner, oldTime, oldTime)
            entityManager.persist(secret)
            val oldVersion = SecretVersion(secret, mapOf("password" to "old-value"), oldTime)
            entityManager.persist(oldVersion)
            entityManager.persist(SecretVersion(
                secret, mapOf("password" to "new-value"), newTime
            ))
            entityManager.persist(ProjectSecretDelegation(secret, project, newTime, newTime))
            entityManager.flush()
            Setup(owner, manager, project.id, project.slug, secret.id, oldVersion.id)
        }

        val references = buildList {
            add(ManagedServiceSecretReference(setup.secretId, "password", "LATEST"))
            if (includePinned) {
                add(ManagedServiceSecretReference(
                    setup.secretId, "password", "PINNED", setup.oldVersionId
                ))
            }
        }
        val managedService = service.create(ManagedServiceCreateCommand(
            projectId = setup.projectId,
            principal = setup.owner,
            name = "service-${UUID.randomUUID().toString().take(8)}",
            configuration = managedServiceDraft(env = mapOf("PLAIN" to "literal"))
                .copy(secretReferences = references)
        ))
        Mockito.reset(containerService)
        return Scenario(
            setup.owner, setup.manager, setup.projectId, setup.projectSlug,
            setup.secretId, managedService.id, references
        )
    }

    private data class Setup(
        val owner: Principal,
        val manager: Principal,
        val projectId: UUID,
        val projectSlug: String,
        val secretId: String,
        val oldVersionId: String
    )

    private data class Scenario(
        val owner: Principal,
        val manager: Principal,
        val projectId: UUID,
        val projectSlug: String,
        val secretId: String,
        val serviceId: UUID,
        val references: List<ManagedServiceSecretReference>
    )

    private class FakeDockerPullException : RuntimeException("fake pull stopped")

    @TestConfiguration
    class MockContainerConfig {
        @Bean
        @Primary
        fun mockContainerService(): ContainerService = Mockito.mock(ContainerService::class.java)
    }
}
