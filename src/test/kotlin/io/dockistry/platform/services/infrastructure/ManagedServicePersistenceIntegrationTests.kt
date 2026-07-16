package io.dockistry.platform.services.infrastructure

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceConfiguration
import io.dockistry.platform.services.domain.ManagedServiceDeployment
import io.dockistry.platform.services.domain.ManagedServiceDeploymentStatus
import io.dockistry.platform.services.domain.ManagedServiceDesiredState
import io.dockistry.platform.services.domain.ServiceEndpoint
import io.dockistry.platform.services.domain.ServiceEndpointMode
import io.dockistry.platform.services.domain.ServiceEndpointProtocol
import io.dockistry.platform.services.domain.ServiceVolumeMount
import io.dockistry.platform.services.testsupport.managedService
import io.dockistry.platform.services.testsupport.managedServiceDraft
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hibernate.Hibernate
import org.junit.jupiter.api.Test
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DataJpaTest
@Import(TestcontainersConfig::class)
@Testcontainers
class ManagedServicePersistenceIntegrationTests {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `managed service configuration survives PostgreSQL JSONB round trip`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val draft = managedServiceDraft(
            imageRef = "Library/Postgres:16-Alpine",
            command = listOf("docker-entrypoint.sh"),
            args = listOf("postgres", "-c", "shared_buffers=256MB"),
            env = mapOf("APP_ENV" to "production", "LOG_LEVEL" to "info"),
            endpoints = listOf(
                ServiceEndpoint(
                    port = 5432,
                    protocol = ServiceEndpointProtocol.TCP,
                    mode = ServiceEndpointMode.DIRECT,
                    externalPort = 15432
                ),
                ServiceEndpoint(
                    port = 8125,
                    protocol = ServiceEndpointProtocol.UDP,
                    mode = ServiceEndpointMode.EDGE
                )
            ),
            volumes = listOf(
                ServiceVolumeMount(name = "postgres.data", mountPath = "/var/lib/postgresql"),
                ServiceVolumeMount(name = "config_01", mountPath = "/etc/app", readOnly = true)
            ),
            restartPolicy = DockerRestartPolicyType.ON_FAILURE,
            restartMaxRetries = 5,
            memoryLimitBytes = 1_073_741_824,
            cpuLimitNanoCpus = 2_000_000_000,
            desiredState = ManagedServiceDesiredState.STOPPED
        )
        val service = managedService(
            project = project,
            name = "postgres-api",
            draft = draft,
            createdAt = CREATED_AT,
            updatedAt = UPDATED_AT
        )
        entityManager.persist(service)
        entityManager.flush()
        val serviceId = service.id
        entityManager.clear()

        val saved = entityManager.find(ManagedService::class.java, serviceId)

        assertNotNull(saved)
        assertEquals(project.id, saved.project.id)
        assertEquals("postgres-api", saved.name)
        assertEquals("library/postgres", saved.imageRepository)
        assertEquals("16-Alpine", saved.imageTag)
        assertEquals("library/postgres:16-Alpine", saved.imageRef.asString())
        assertEquals(draft.command, saved.command)
        assertEquals(draft.args, saved.args)
        assertEquals(draft.env, saved.env)
        assertEquals(draft.endpoints, saved.endpoints)
        assertEquals(draft.volumes, saved.volumes)
        assertEquals(DockerRestartPolicyType.ON_FAILURE, saved.restartPolicy)
        assertEquals(5, saved.restartMaxRetries)
        assertEquals(1_073_741_824, saved.memoryLimitBytes)
        assertEquals(2_000_000_000, saved.cpuLimitNanoCpus)
        assertEquals(ManagedServiceDesiredState.STOPPED, saved.desiredState)
        assertEquals(0, saved.version)
        assertEquals(CREATED_AT, saved.createdAt)
        assertEquals(UPDATED_AT, saved.updatedAt)

        val jsonColumnTypes = entityManager.createNativeQuery(
            """
                SELECT pg_typeof(command)::text,
                       pg_typeof(args)::text,
                       pg_typeof(env)::text,
                       pg_typeof(endpoints)::text,
                       pg_typeof(volumes)::text
                  FROM services
                 WHERE id = :serviceId
            """.trimIndent()
        )
            .setParameter("serviceId", serviceId)
            .singleResult as Array<*>

        assertEquals(listOf("jsonb", "jsonb", "jsonb", "jsonb", "jsonb"), jsonColumnTypes.toList())
    }

    @Test
    fun `managed service remains proxyable and lazy getter initializes reference`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val service = managedService(
            project = project,
            name = "proxy-service",
            draft = managedServiceDraft(args = listOf("--serve"))
        )
        entityManager.persist(service)
        entityManager.flush()
        val serviceId = service.id
        entityManager.clear()

        val reference = entityManager.getReference(ManagedService::class.java, serviceId)

        assertFalse(Hibernate.isInitialized(reference))
        assertEquals(listOf("--serve"), reference.args)
        assertTrue(Hibernate.isInitialized(reference))
    }

    @Test
    fun `managed service deployment survives PostgreSQL JSONB round trip`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val draft = managedServiceDraft(
            imageRef = "nginx:1.29-alpine",
            command = listOf("nginx"),
            args = listOf("-g", "daemon off;"),
            env = mapOf("APP_ENV" to "production"),
            endpoints = listOf(
                ServiceEndpoint(
                    port = 80,
                    protocol = ServiceEndpointProtocol.TCP,
                    mode = ServiceEndpointMode.DIRECT,
                    externalPort = 8080
                )
            ),
            volumes = listOf(
                ServiceVolumeMount(name = "nginx-data", mountPath = "/usr/share/nginx/html")
            ),
            restartPolicy = DockerRestartPolicyType.UNLESS_STOPPED,
            memoryLimitBytes = 536_870_912,
            cpuLimitNanoCpus = 1_000_000_000
        )
        val service = managedService(
            project = project,
            name = "nginx-web",
            draft = draft,
            createdAt = CREATED_AT,
            updatedAt = UPDATED_AT
        )
        entityManager.persist(service)
        entityManager.flush()

        val deployment = ManagedServiceDeployment(
            service = service,
            configSnapshot = ManagedServiceConfiguration.validated(draft),
            serviceVersion = service.version,
            createdAt = CREATED_AT,
            updatedAt = UPDATED_AT
        ).also {
            it.containerId = "container-123"
            it.status = ManagedServiceDeploymentStatus.FINISHED
        }
        entityManager.persist(deployment)
        entityManager.flush()
        val deploymentId = deployment.id
        entityManager.clear()

        val saved = entityManager.find(ManagedServiceDeployment::class.java, deploymentId)

        assertNotNull(saved)
        assertEquals(service.id, saved.service.id)
        assertEquals("container-123", saved.containerId)
        assertEquals("nginx", saved.imageRepository)
        assertEquals("1.29-alpine", saved.imageTag)
        assertEquals(service.version, saved.serviceVersion)
        assertEquals(ManagedServiceDeploymentStatus.FINISHED, saved.status)
        assertEquals(CREATED_AT, saved.createdAt)
        assertEquals(UPDATED_AT, saved.updatedAt)
        assertEquals("nginx:1.29-alpine", saved.configSnapshot.imageRef.asString())
        assertEquals(draft.command, saved.configSnapshot.command)
        assertEquals(draft.args, saved.configSnapshot.args)
        assertEquals(draft.env, saved.configSnapshot.env)
        assertEquals(draft.endpoints, saved.configSnapshot.endpoints)
        assertEquals(draft.volumes, saved.configSnapshot.volumes)
        assertEquals(draft.restartPolicy, saved.configSnapshot.restartPolicy)
        assertEquals(draft.memoryLimitBytes, saved.configSnapshot.memoryLimitBytes)
        assertEquals(draft.cpuLimitNanoCpus, saved.configSnapshot.cpuLimitNanoCpus)
    }

    companion object {
        private val CREATED_AT = Instant.parse("2026-01-01T00:00:00Z")
        private val UPDATED_AT = Instant.parse("2026-01-01T01:00:00Z")
    }
}
