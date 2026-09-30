package io.dockistry.platform.services.api

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.auth.application.TokenManagementService
import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceConfigurationDraft
import io.dockistry.platform.services.domain.ManagedServiceDesiredState
import io.dockistry.platform.services.domain.ServiceEndpoint
import io.dockistry.platform.services.domain.ServiceEndpointMode
import io.dockistry.platform.services.domain.ServiceEndpointProtocol
import io.dockistry.platform.services.domain.ServiceVolumeMount
import io.dockistry.platform.services.domain.dto.ManagedServiceCreateRequest
import io.dockistry.platform.services.domain.dto.ManagedServiceUpdateRequest
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import io.dockistry.platform.services.testsupport.managedService
import io.dockistry.platform.services.testsupport.managedServiceDraft
import io.dockistry.platform.testsupport.IamProjectFixtures
import io.dockistry.platform.testsupport.MutableClock
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, TestClockConfig::class)
@Testcontainers
@Transactional
class ManagedServiceControllerIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var tokenManagementService: TokenManagementService

    @Autowired
    private lateinit var managedServiceRepository: ManagedServiceRepository

    @Autowired
    private lateinit var clock: MutableClock

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @BeforeEach
    fun resetClock() {
        clock.set(BASE_TIME)
    }

    @Test
    fun `create minimal service normalizes image and applies defaults`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "service-creator")
        val project = fixtures.project(ownerPrincipal = user.user.principal)
        fixtures.grantManage(user.user.principal, project)
        fixtures.flush()

        mockMvc.post(servicesPath(project)) {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = createJson(
                ManagedServiceCreateRequest(
                    name = "web",
                    imageRef = "  NGINX  "
                )
            )
        }.andExpect {
            status { isCreated() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.data.id") { exists() }
            jsonPath("$.data.projectId") { value(project.id.toString()) }
            jsonPath("$.data.name") { value("web") }
            jsonPath("$.data.imageRef") { value("nginx:latest") }
            jsonPath("$.data.command") { value(nullValue()) }
            jsonPath("$.data.args") { value(nullValue()) }
            jsonPath("$.data.env") { isEmpty() }
            jsonPath("$.data.endpoints") { isEmpty() }
            jsonPath("$.data.volumes") { isEmpty() }
            jsonPath("$.data.restartPolicy") {
                value(DockerRestartPolicyType.UNLESS_STOPPED.name)
            }
            jsonPath("$.data.restartMaxRetries") { value(nullValue()) }
            jsonPath("$.data.memoryLimitBytes") { value(nullValue()) }
            jsonPath("$.data.cpuLimitNanoCpus") { value(nullValue()) }
            jsonPath("$.data.desiredState") {
                value(ManagedServiceDesiredState.RUNNING.name)
            }
            jsonPath("$.data.version") { value(0) }
            jsonPath("$.data.createdAt") { value(BASE_TIME.toString()) }
            jsonPath("$.data.updatedAt") { value(BASE_TIME.toString()) }
            expectNoError()
        }
    }

    @Test
    fun `create persists full typed configuration including cross protocol port reuse`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "rich-service-creator")
        val project = fixtures.project(ownerPrincipal = user.user.principal)
        fixtures.grantManage(user.user.principal, project)
        fixtures.flush()
        val endpoints = listOf(
            ServiceEndpoint(
                port = 8080,
                protocol = ServiceEndpointProtocol.TCP,
                mode = ServiceEndpointMode.DIRECT,
                externalPort = 18080
            ),
            ServiceEndpoint(
                port = 5353,
                protocol = ServiceEndpointProtocol.UDP,
                mode = ServiceEndpointMode.DIRECT,
                externalPort = 18080
            ),
            ServiceEndpoint(port = 9090, mode = ServiceEndpointMode.EDGE)
        )
        val volumes = listOf(
            ServiceVolumeMount(name = "app.data", mountPath = "/var/lib/app"),
            ServiceVolumeMount(name = "cache_01", mountPath = "/cache", readOnly = true)
        )
        val request = ManagedServiceCreateRequest(
            name = "api",
            imageRef = "Library/Postgres:16-Alpine",
            command = listOf("docker-entrypoint.sh"),
            args = listOf("postgres", "-c", "shared_buffers=256MB"),
            env = mapOf("APP_ENV" to "production", "LOG_LEVEL" to "info"),
            endpoints = endpoints,
            volumes = volumes,
            restartPolicy = DockerRestartPolicyType.ON_FAILURE,
            restartMaxRetries = 3,
            memoryLimitBytes = 536_870_912,
            cpuLimitNanoCpus = 1_500_000_000,
            desiredState = ManagedServiceDesiredState.STOPPED
        )

        mockMvc.post(servicesPath(project)) {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = createJson(request)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.imageRef") { value("library/postgres:16-Alpine") }
            jsonPath("$.data.endpoints", hasSize<Any>(3))
            jsonPath("$.data.volumes", hasSize<Any>(2))
            jsonPath("$.data.restartPolicy") {
                value(DockerRestartPolicyType.ON_FAILURE.name)
            }
            jsonPath("$.data.restartMaxRetries") { value(3) }
            jsonPath("$.data.desiredState") {
                value(ManagedServiceDesiredState.STOPPED.name)
            }
            expectNoError()
        }

        fixtures.flushAndClear()
        val saved = managedServiceRepository.findAllByProjectId(project.id).single()

        assertEquals("library/postgres", saved.imageRepository)
        assertEquals("16-Alpine", saved.imageTag)
        assertEquals(request.command, saved.command)
        assertEquals(request.args, saved.args)
        assertEquals(request.env, saved.env)
        assertEquals(endpoints, saved.endpoints)
        assertEquals(volumes, saved.volumes)
        assertEquals(DockerRestartPolicyType.ON_FAILURE, saved.restartPolicy)
        assertEquals(3, saved.restartMaxRetries)
        assertEquals(536_870_912, saved.memoryLimitBytes)
        assertEquals(1_500_000_000, saved.cpuLimitNanoCpus)
        assertEquals(ManagedServiceDesiredState.STOPPED, saved.desiredState)
        assertEquals(0, saved.version)
    }

    @Test
    fun `list and get return only services from requested readable project`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "service-reader")
        val firstProject = fixtures.project()
        val secondProject = fixtures.project()
        fixtures.grantRead(user.user.principal, firstProject)
        fixtures.grantRead(user.user.principal, secondProject)
        val firstService = persistService(firstProject, "first-api")
        val secondService = persistService(secondProject, "second-api")
        fixtures.flush()

        mockMvc.get(servicesPath(firstProject)) {
            bearer(user)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data", hasSize<Any>(1))
            jsonPath("$.data[0].id") { value(firstService.id.toString()) }
            jsonPath("$.data[0].name") { value(firstService.name) }
            jsonPath("$.data[0].id") {
                value(not(secondService.id.toString()))
            }
            expectNoError()
        }

        mockMvc.get(servicePath(firstProject, firstService.id)) {
            bearer(user)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(firstService.id.toString()) }
            jsonPath("$.data.projectId") { value(firstProject.id.toString()) }
            jsonPath("$.data.name") { value(firstService.name) }
            expectNoError()
        }
    }

    @Test
    fun `full update replaces mutable configuration and keeps name immutable`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "service-updater")
        val project = fixtures.project(ownerPrincipal = user.user.principal)
        fixtures.grantManage(user.user.principal, project)
        val service = persistService(project, "immutable-name")
        fixtures.flush()
        val serviceId = service.id
        val updatedAt = BASE_TIME.plus(Duration.ofSeconds(10))
        clock.advance(Duration.ofSeconds(10))
        val request = richUpdateRequest(expectedVersion = 0)

        mockMvc.patch(servicePath(project, serviceId)) {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = updateJson(request)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(serviceId.toString()) }
            jsonPath("$.data.name") { value("immutable-name") }
            jsonPath("$.data.imageRef") { value("redis:7") }
            jsonPath("$.data.command[0]") { value("redis-server") }
            jsonPath("$.data.args[0]") { value("--appendonly") }
            jsonPath("$.data.env.LOG_LEVEL") { value("debug") }
            jsonPath("$.data.endpoints", hasSize<Any>(1))
            jsonPath("$.data.volumes", hasSize<Any>(1))
            jsonPath("$.data.restartPolicy") {
                value(DockerRestartPolicyType.ALWAYS.name)
            }
            jsonPath("$.data.desiredState") {
                value(ManagedServiceDesiredState.STOPPED.name)
            }
            jsonPath("$.data.version") { value(1) }
            jsonPath("$.data.updatedAt") { value(updatedAt.toString()) }
            expectNoError()
        }

        fixtures.flushAndClear()
        val saved = managedServiceRepository.findById(serviceId).orElseThrow()
        assertEquals("immutable-name", saved.name)
        assertEquals("redis:7", saved.imageRef.asString())
        assertEquals(request.command, saved.command)
        assertEquals(request.args, saved.args)
        assertEquals(request.env, saved.env)
        assertEquals(request.endpoints, saved.endpoints)
        assertEquals(request.volumes, saved.volumes)
        assertEquals(1, saved.version)
        assertEquals(updatedAt, saved.updatedAt)
    }

    @Test
    fun `repeated stale update returns current state without another write`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "idempotent-updater")
        val project = fixtures.project(ownerPrincipal = user.user.principal)
        fixtures.grantManage(user.user.principal, project)
        val service = persistService(project, "repeatable")
        fixtures.flush()
        val serviceId = service.id
        val firstUpdatedAt = BASE_TIME.plus(Duration.ofSeconds(10))
        clock.advance(Duration.ofSeconds(10))
        val request = richUpdateRequest(expectedVersion = 0)

        mockMvc.patch(servicePath(project, serviceId)) {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = updateJson(request)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.version") { value(1) }
            jsonPath("$.data.updatedAt") { value(firstUpdatedAt.toString()) }
        }

        clock.advance(Duration.ofSeconds(20))
        mockMvc.patch(servicePath(project, serviceId)) {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = updateJson(request)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.version") { value(1) }
            jsonPath("$.data.updatedAt") { value(firstUpdatedAt.toString()) }
            expectNoError()
        }

        fixtures.flushAndClear()
        val saved = managedServiceRepository.findById(serviceId).orElseThrow()
        assertEquals(1, saved.version)
        assertEquals(firstUpdatedAt, saved.updatedAt)
    }

    @Test
    fun `delete removes existing service`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "service-deleter")
        val project = fixtures.project(ownerPrincipal = user.user.principal)
        fixtures.grantManage(user.user.principal, project)
        val service = persistService(project, "deleted-service")
        fixtures.flush()

        mockMvc.delete(servicePath(project, service.id)) {
            bearer(user)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data") { value("ok") }
            expectNoError()
        }

        assertFalse(managedServiceRepository.existsById(service.id))
    }

    @Test
    fun `group derived permissions allow service creation`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "group-service-manager")
        val group = fixtures.group()
        user.user.groups += group
        val project = fixtures.project()
        fixtures.grantSystemPermission(
            group.principal,
            project.scope,
            SystemPermissionNames.PROJECT_READ.key,
            SystemPermissionNames.PROJECT_SERVICES_MANAGE.key
        )
        fixtures.flush()

        mockMvc.post(servicesPath(project)) {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = createJson(
                ManagedServiceCreateRequest(name = "group-api", imageRef = "nginx")
            )
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.name") { value("group-api") }
            expectNoError()
        }
    }

    @Test
    fun `service endpoints require authentication`() {
        mockMvc.get("/api/projects/${UUID.randomUUID()}/services").andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.data") { value(nullValue()) }
            jsonPath("$.errorMessage") {
                value("Full authentication is required to access this resource")
            }
            jsonPath("$.errorCode") { value("authentication_required") }
        }
    }

    @Test
    fun `mutations hide unreadable projects and forbid read only users`() {
        val fixtures = fixtures()
        val hiddenUser = authenticatedUser(fixtures, "hidden-service-user")
        val readOnlyUser = authenticatedUser(fixtures, "readonly-service-user")
        val project = fixtures.project()
        fixtures.grantRead(readOnlyUser.user.principal, project)
        fixtures.flush()
        val request = ManagedServiceCreateRequest(name = "api", imageRef = "nginx")

        mockMvc.post(servicesPath(project)) {
            bearer(hiddenUser)
            contentType = MediaType.APPLICATION_JSON
            content = createJson(request)
        }.andExpect {
            expectApiError(404, "project_not_found")
        }

        mockMvc.post(servicesPath(project)) {
            bearer(readOnlyUser)
            contentType = MediaType.APPLICATION_JSON
            content = createJson(request)
        }.andExpect {
            expectApiError(403, "insufficient_permission_for_operation")
        }
    }

    @Test
    fun `get returns not found for missing service and wrong project`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "service-reader")
        val firstProject = fixtures.project()
        val secondProject = fixtures.project()
        fixtures.grantRead(user.user.principal, firstProject)
        fixtures.grantRead(user.user.principal, secondProject)
        val service = persistService(firstProject, "first-service")
        fixtures.flush()

        mockMvc.get(servicePath(firstProject, UUID.randomUUID())) {
            bearer(user)
        }.andExpect {
            expectApiError(404, "managed_service_not_found")
        }

        mockMvc.get(servicePath(secondProject, service.id)) {
            bearer(user)
        }.andExpect {
            expectApiError(404, "managed_service_not_found")
        }
    }

    @Test
    fun `service name is unique only within project`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "duplicate-service-creator")
        val firstProject = fixtures.project()
        val secondProject = fixtures.project()
        fixtures.grantManage(user.user.principal, firstProject)
        fixtures.grantManage(user.user.principal, secondProject)
        persistService(firstProject, "shared-name")
        fixtures.flush()
        val request = ManagedServiceCreateRequest(
            name = "shared-name",
            imageRef = "nginx"
        )

        mockMvc.post(servicesPath(firstProject)) {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = createJson(request)
        }.andExpect {
            expectApiError(409, "managed_service_already_exists")
        }

        mockMvc.post(servicesPath(secondProject)) {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = createJson(request)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.name") { value("shared-name") }
            expectNoError()
        }
    }

    @Test
    fun `stale update with different configuration returns conflict without mutation`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "conflicting-updater")
        val project = fixtures.project()
        fixtures.grantManage(user.user.principal, project)
        val service = persistService(project, "conflicting-service")
        fixtures.flush()
        val serviceId = service.id
        val acceptedRequest = richUpdateRequest(expectedVersion = 0)

        mockMvc.patch(servicePath(project, serviceId)) {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = updateJson(acceptedRequest)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.version") { value(1) }
        }

        val conflictingRequest = ManagedServiceUpdateRequest(
            expectedVersion = 0,
            imageRef = "postgres:17"
        )
        clock.advance(Duration.ofSeconds(5))
        mockMvc.patch(servicePath(project, serviceId)) {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = updateJson(conflictingRequest)
        }.andExpect {
            expectApiError(409, "managed_service_version_conflict")
        }

        fixtures.flushAndClear()
        val saved = managedServiceRepository.findById(serviceId).orElseThrow()
        assertEquals("redis:7", saved.imageRef.asString())
        assertEquals(acceptedRequest.command, saved.command)
        assertEquals(1, saved.version)
        assertEquals(BASE_TIME, saved.updatedAt)
    }

    @Test
    fun `update rejects missing and negative expected version`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "version-validator")
        val project = fixtures.project()
        fixtures.grantManage(user.user.principal, project)
        val service = persistService(project, "versioned-service")
        fixtures.flush()

        mockMvc.patch(servicePath(project, service.id)) {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("imageRef" to "nginx"))
        }.andExpect {
            expectApiError(400, "bad_request")
        }

        mockMvc.patch(servicePath(project, service.id)) {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = updateJson(
                ManagedServiceUpdateRequest(expectedVersion = -1, imageRef = "nginx")
            )
        }.andExpect {
            expectApiError(400, "invalid_managed_service_config")
        }
    }

    @Test
    fun `create returns typed errors for invalid name image and configuration`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "invalid-service-creator")
        val project = fixtures.project()
        fixtures.grantManage(user.user.principal, project)
        fixtures.flush()

        val invalidRequests = listOf(
            ManagedServiceCreateRequest(name = "Invalid Name", imageRef = "nginx") to
                "invalid_managed_service_name",
            ManagedServiceCreateRequest(name = "api", imageRef = "ghcr.io/team/api") to
                "invalid_managed_service_image_ref",
            ManagedServiceCreateRequest(
                name = "api",
                imageRef = "nginx",
                endpoints = listOf(
                    ServiceEndpoint(port = 8080, mode = ServiceEndpointMode.DIRECT)
                )
            ) to "invalid_managed_service_config"
        )

        invalidRequests.forEach { (request, errorCode) ->
            mockMvc.post(servicesPath(project)) {
                bearer(user)
                contentType = MediaType.APPLICATION_JSON
                content = createJson(request)
            }.andExpect {
                expectApiError(400, errorCode)
            }
        }
    }

    @Test
    fun `delete missing service returns not found`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "missing-service-deleter")
        val project = fixtures.project()
        fixtures.grantManage(user.user.principal, project)
        fixtures.flush()

        mockMvc.delete(servicePath(project, UUID.randomUUID())) {
            bearer(user)
        }.andExpect {
            expectApiError(404, "managed_service_not_found")
        }
    }

    private fun fixtures(): IamProjectFixtures = IamProjectFixtures(entityManager)

    private fun persistService(
        project: Project,
        name: String,
        draft: ManagedServiceConfigurationDraft = managedServiceDraft()
    ): ManagedService {
        return managedService(project, name, draft).also(entityManager::persist)
    }

    private fun authenticatedUser(
        fixtures: IamProjectFixtures,
        prefix: String
    ): AuthenticatedTestUser {
        val suffix = fixtures.unique().take(8)
        val username = "${prefix.take(20)}-$suffix"
        val user = fixtures.user(
            username = username,
            email = "$username@dockistry.io"
        ).apply {
            emailVerifiedAt = BASE_TIME
        }
        fixtures.flush()

        return AuthenticatedTestUser(
            user = user,
            accessToken = tokenManagementService.issueTokenPair(user.id).accessToken
        )
    }

    private fun IamProjectFixtures.grantRead(principal: Principal, project: Project) {
        grantSystemPermission(
            principal,
            project.scope,
            SystemPermissionNames.PROJECT_READ.key
        )
    }

    private fun IamProjectFixtures.grantManage(principal: Principal, project: Project) {
        grantSystemPermission(
            principal,
            project.scope,
            SystemPermissionNames.PROJECT_READ.key,
            SystemPermissionNames.PROJECT_SERVICES_MANAGE.key
        )
    }

    private fun richUpdateRequest(expectedVersion: Long): ManagedServiceUpdateRequest {
        return ManagedServiceUpdateRequest(
            expectedVersion = expectedVersion,
            imageRef = "redis:7",
            command = listOf("redis-server"),
            args = listOf("--appendonly", "yes"),
            env = mapOf("LOG_LEVEL" to "debug"),
            endpoints = listOf(
                ServiceEndpoint(
                    port = 6379,
                    mode = ServiceEndpointMode.DIRECT,
                    externalPort = 16379
                )
            ),
            volumes = listOf(
                ServiceVolumeMount(name = "redis-data", mountPath = "/data")
            ),
            restartPolicy = DockerRestartPolicyType.ALWAYS,
            memoryLimitBytes = 268_435_456,
            cpuLimitNanoCpus = 750_000_000,
            desiredState = ManagedServiceDesiredState.STOPPED
        )
    }

    private fun createJson(request: ManagedServiceCreateRequest): String {
        return objectMapper.writeValueAsString(request)
    }

    private fun updateJson(request: ManagedServiceUpdateRequest): String {
        return objectMapper.writeValueAsString(request)
    }

    private fun servicesPath(project: Project): String {
        return "/api/projects/${project.id}/services"
    }

    private fun servicePath(project: Project, serviceId: UUID): String {
        return "${servicesPath(project)}/$serviceId"
    }

    private fun MockHttpServletRequestDsl.bearer(user: AuthenticatedTestUser) {
        header(HttpHeaders.AUTHORIZATION, "Bearer ${user.accessToken}")
    }

    private fun MockMvcResultMatchersDsl.expectNoError() {
        jsonPath("$.errorMessage") { value(nullValue()) }
        jsonPath("$.errorCode") { value(nullValue()) }
    }

    private fun MockMvcResultMatchersDsl.expectApiError(
        statusCode: Int,
        errorCode: String
    ) {
        status { isEqualTo(statusCode) }
        content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        jsonPath("$.data") { value(nullValue()) }
        jsonPath("$.errorMessage") { exists() }
        jsonPath("$.errorCode") { value(errorCode) }
    }

    private data class AuthenticatedTestUser(
        val user: User,
        val accessToken: String
    )

    companion object {
        private val BASE_TIME: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
