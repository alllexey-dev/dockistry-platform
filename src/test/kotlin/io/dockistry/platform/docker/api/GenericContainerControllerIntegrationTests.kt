package io.dockistry.platform.docker.api

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.auth.application.TokenManagementService
import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerContainerAlreadyStartedException
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.model.DockerAgentInfo
import io.dockistry.platform.docker.domain.model.DockerContainerConfig
import io.dockistry.platform.docker.domain.model.DockerContainerCreateCommand
import io.dockistry.platform.docker.domain.model.DockerContainerDetails
import io.dockistry.platform.docker.domain.model.DockerContainerHostConfig
import io.dockistry.platform.docker.domain.model.DockerContainerLogs
import io.dockistry.platform.docker.domain.model.DockerContainerLogsRequest
import io.dockistry.platform.docker.domain.model.DockerContainerState
import io.dockistry.platform.docker.domain.model.DockerContainerStateDetails
import io.dockistry.platform.docker.domain.model.DockerContainerStats
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerImageInfo
import io.dockistry.platform.docker.domain.model.DockerImageRef
import io.dockistry.platform.docker.domain.model.DockerImageSummary
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.docker.domain.model.DockerNetworkRef
import io.dockistry.platform.docker.domain.model.DockerRestartPolicy
import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType
import io.dockistry.platform.docker.domain.model.DockerVolumeRef
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(
    TestcontainersConfig::class,
    GenericContainerControllerIntegrationTests.FakeDockerAgentClientConfig::class
)
@Testcontainers
@Transactional
class GenericContainerControllerIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var tokenManagementService: TokenManagementService

    @Autowired
    private lateinit var dockerAgentClient: FakeDockerAgentClient

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @BeforeEach
    fun resetDockerAgentClient() {
        dockerAgentClient.reset()
    }

    @Test
    fun `repeated start returns conflict with explicit error code`() {
        val user = genericContainerManager("container-manager")
        val container = dockerAgentClient.addContainer("repeat-start")
        val endpoint = "/api/docker/containers/${container.id}/start"

        mockMvc.post(endpoint) {
            bearer(user)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.data") { value("ok") }
            jsonPath("$.errorMessage") { value(nullValue()) }
            jsonPath("$.errorCode") { value(nullValue()) }
        }

        mockMvc.post(endpoint) {
            bearer(user)
        }.andExpect {
            status { isConflict() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.data") { value(nullValue()) }
            jsonPath("$.errorMessage") { exists() }
            jsonPath("$.errorCode") { value("docker_container_already_started") }
        }
    }

    @Test
    fun `every generic endpoint rejects Dockistry-labeled containers`() {
        val user = genericContainerManager("managed-reject")
        val managedContainer = dockerAgentClient.addContainer(
            name = "managed-service-container",
            labels = DockerLabels.forDeployment(
                projectId = UUID.randomUUID(),
                serviceId = UUID.randomUUID(),
                deploymentId = UUID.randomUUID()
            )
        )
        val genericContainer = dockerAgentClient.addContainer("generic-container")
        val endpoint = "/api/docker/containers/${managedContainer.id}"

        mockMvc.get("/api/docker/containers") {
            bearer(user)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].id") { value(genericContainer.id) }
        }

        assertAll(
            "Every direct generic-container endpoint must reject managed containers",
            listOf(
                Executable {
                    mockMvc.get(endpoint) { bearer(user) }
                        .andExpectDockistryManagedForbidden()
                },
                Executable {
                    mockMvc.get("$endpoint/stats") { bearer(user) }
                        .andExpectDockistryManagedForbidden()
                },
                Executable {
                    mockMvc.get("$endpoint/logs") { bearer(user) }
                        .andExpectDockistryManagedForbidden()
                },
                Executable {
                    mockMvc.post("$endpoint/start") { bearer(user) }
                        .andExpectDockistryManagedForbidden()
                },
                Executable {
                    mockMvc.post("$endpoint/stop") { bearer(user) }
                        .andExpectDockistryManagedForbidden()
                },
                Executable {
                    mockMvc.post("$endpoint/restart") { bearer(user) }
                        .andExpectDockistryManagedForbidden()
                },
                Executable {
                    mockMvc.post("$endpoint/pause") { bearer(user) }
                        .andExpectDockistryManagedForbidden()
                },
                Executable {
                    mockMvc.post("$endpoint/unpause") { bearer(user) }
                        .andExpectDockistryManagedForbidden()
                },
                Executable {
                    mockMvc.delete(endpoint) { bearer(user) }
                        .andExpectDockistryManagedForbidden()
                }
            )
        )
    }

    @Test
    fun `generic start stop and delete accept unlabeled containers by full id`() {
        val user = genericContainerManager("generic-lifecycle")
        val startContainer = dockerAgentClient.addContainer("start-container")
        val stopContainer = dockerAgentClient.addContainer("stop-container")
        val deleteContainer = dockerAgentClient.addContainer("delete-container")

        mockMvc.post("/api/docker/containers/${startContainer.id}/start") {
            bearer(user)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data") { value("ok") }
        }
        mockMvc.post("/api/docker/containers/${stopContainer.id}/stop") {
            bearer(user)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data") { value("ok") }
        }
        mockMvc.delete("/api/docker/containers/${deleteContainer.id}") {
            bearer(user)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data") { value("ok") }
        }

        assertTrue(dockerAgentClient.wasStarted(startContainer.id))
        assertTrue(dockerAgentClient.wasStopped(stopContainer.id))
        assertTrue(dockerAgentClient.wasDeleted(deleteContainer.id))
    }

    @Test
    fun `generic lifecycle rejects container names and abbreviated ids`() {
        val user = genericContainerManager("full-id")
        val container = dockerAgentClient.addContainer("container-by-name")
        val references = listOf(container.name, container.id.take(12))

        assertAll(
            "Only a container's immutable full ID may be used",
            references.map { reference ->
                Executable {
                    mockMvc.post("/api/docker/containers/$reference/start") {
                        bearer(user)
                    }.andExpect {
                        status { isNotFound() }
                        jsonPath("$.errorCode") { value("docker_container_not_found") }
                    }
                }
            }
        )
        assertFalse(dockerAgentClient.wasStarted(container.id))
    }

    private fun genericContainerManager(prefix: String): User {
        val fixtures = IamProjectFixtures(entityManager)
        val user = authenticatedUser(fixtures, prefix)
        fixtures.grantSystemPermission(
            user.principal,
            fixtures.globalScope(),
            SystemPermissionNames.DOCKER_GENERIC_CONTAINERS_MANAGE
        )
        fixtures.flush()
        return user
    }

    private fun ResultActionsDsl.andExpectDockistryManagedForbidden() {
        andExpect {
            status { isForbidden() }
            jsonPath("$.data") { value(nullValue()) }
            jsonPath("$.errorCode") {
                value("dockistry_managed_container_access_forbidden")
            }
        }
    }

    private fun authenticatedUser(
        fixtures: IamProjectFixtures,
        prefix: String
    ): User {
        val suffix = fixtures.unique()
        val user = fixtures.user(
            username = "$prefix-$suffix",
            email = "$prefix-$suffix@dockistry.io"
        ).apply {
            emailVerifiedAt = Instant.parse("2026-01-01T00:00:00Z")
        }
        fixtures.flush()

        tokenManagementService.issueTokenPair(user.id)
        return user
    }

    private fun MockHttpServletRequestDsl.bearer(user: User) {
        val accessToken = tokenManagementService.issueTokenPair(user.id).accessToken
        header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
    }

    @TestConfiguration
    class FakeDockerAgentClientConfig {
        @Bean
        @Primary
        fun fakeDockerAgentClient(): FakeDockerAgentClient {
            return FakeDockerAgentClient()
        }
    }

    class FakeDockerAgentClient : DockerAgentClient {
        private val containers = linkedMapOf<String, TestContainer>()
        private val startedContainerIds = mutableSetOf<String>()
        private val stoppedContainerIds = mutableSetOf<String>()
        private val deletedContainerIds = mutableSetOf<String>()

        fun reset() {
            containers.clear()
            startedContainerIds.clear()
            stoppedContainerIds.clear()
            deletedContainerIds.clear()
        }

        fun addContainer(
            name: String,
            labels: Map<String, String> = emptyMap()
        ): TestContainer {
            return TestContainer(
                id = newFullContainerId(),
                name = name,
                labels = labels
            ).also {
                containers[it.id] = it
            }
        }

        fun wasStarted(containerId: String): Boolean =
            startedContainerIds.contains(containerId)

        fun wasStopped(containerId: String): Boolean =
            stoppedContainerIds.contains(containerId)

        fun wasDeleted(containerId: String): Boolean =
            deletedContainerIds.contains(containerId)

        override fun status(): DockerAgentInfo =
            error("Docker status is not used in this test")

        override fun listImages(): List<DockerImageSummary> =
            error("Images are not used in this test")

        override fun inspectImage(imageId: String): DockerImageInfo =
            error("Images are not used in this test")

        override fun pullImage(imageRef: DockerImageRef): Unit =
            error("Images are not used in this test")

        override fun removeImage(imageId: String, force: Boolean): Unit =
            error("Images are not used in this test")

        override fun findNetworkByName(name: String): DockerNetworkRef =
            error("Network lifecycle is not used in this test")

        override fun createNetwork(
            name: String,
            labels: Map<String, String>
        ): DockerNetworkRef =
            error("Network lifecycle is not used in this test")

        override fun removeNetwork(networkId: String): Unit =
            error("Network lifecycle is not used in this test")

        override fun createVolume(
            name: String,
            labels: Map<String, String>
        ): Unit = error("Volume lifecycle is not used in this test")

        override fun listVolumes(filter: DockerLabelsFilter): List<DockerVolumeRef> =
            error("Volume lifecycle is not used in this test")

        override fun findVolumeByName(name: String): DockerVolumeRef =
            error("Volume lifecycle is not used in this test")

        override fun removeVolume(name: String): Unit =
            error("Volume lifecycle is not used in this test")

        override fun createContainer(command: DockerContainerCreateCommand): String =
            error("Container creation is not used in this test")

        override fun listContainers(filter: DockerLabelsFilter): List<DockerContainerSummary> {
            return containers.values
                .filter { container ->
                    filter.labels.all { (key, value) ->
                        container.labels[key] == value
                    }
                }
                .map(::containerSummary)
        }

        override fun inspectContainer(containerId: String): DockerContainerDetails =
            containerDetails(findContainer(containerId))

        override fun getContainerStats(containerId: String): DockerContainerStats =
            error("Stats are not used in this test")

        override fun getLogs(
            containerId: String,
            request: DockerContainerLogsRequest
        ): DockerContainerLogs = error("Logs are not used in this test")

        override fun startContainer(containerId: String) {
            val container = findContainer(containerId)
            if (!startedContainerIds.add(container.id)) {
                throw DockerContainerAlreadyStartedException()
            }
        }

        override fun stopContainer(containerId: String) {
            stoppedContainerIds.add(findContainer(containerId).id)
        }

        override fun restartContainer(containerId: String): Unit =
            error("Restart is not used for a generic container in this test")

        override fun pauseContainer(containerId: String): Unit =
            error("Pause is not used for a generic container in this test")

        override fun unpauseContainer(containerId: String): Unit =
            error("Unpause is not used for a generic container in this test")

        override fun deleteContainer(containerId: String) {
            val container = findContainer(containerId)
            containers.remove(container.id)
            deletedContainerIds.add(container.id)
        }

        private fun findContainer(reference: String): TestContainer {
            return containers.values.singleOrNull { container ->
                container.id == reference ||
                        container.id.startsWith(reference) ||
                        container.name == reference
            } ?: throw DockerContainerNotFoundException(
                "Docker container couldn't be found"
            )
        }

        private fun containerSummary(container: TestContainer): DockerContainerSummary {
            return DockerContainerSummary(
                id = container.id,
                name = container.name,
                names = listOf(container.name),
                image = "busybox:1.36",
                imageId = null,
                command = null,
                createdEpochSeconds = null,
                state = DockerContainerState.RUNNING,
                status = "running",
                ports = emptyList(),
                labels = container.labels,
                sizeRwBytes = null,
                sizeRootFsBytes = null,
                networkMode = null,
                networks = emptyMap(),
                mounts = emptyList()
            )
        }

        private fun containerDetails(container: TestContainer): DockerContainerDetails {
            return DockerContainerDetails(
                id = container.id,
                name = container.name,
                image = "busybox:1.36",
                imageId = null,
                createdAt = null,
                path = null,
                args = emptyList(),
                driver = null,
                platform = null,
                state = DockerContainerStateDetails(
                    state = DockerContainerState.RUNNING,
                    statusText = "running",
                    running = true,
                    paused = false,
                    restarting = false,
                    oomKilled = false,
                    dead = false,
                    pid = null,
                    exitCode = null,
                    error = null,
                    startedAt = null,
                    finishedAt = null,
                    health = null
                ),
                config = DockerContainerConfig(
                    image = "busybox:1.36",
                    env = emptyMap(),
                    envNames = emptyList(),
                    cmd = emptyList(),
                    entrypoint = emptyList(),
                    workingDir = null,
                    user = null,
                    hostname = null,
                    labels = container.labels,
                    exposedPorts = emptyList(),
                    healthcheck = null
                ),
                hostConfig = DockerContainerHostConfig(
                    networkMode = null,
                    privileged = null,
                    readonlyRootfs = null,
                    autoRemove = null,
                    publishAllPorts = null,
                    restartPolicy = DockerRestartPolicy(DockerRestartPolicyType.NO),
                    memoryBytes = null,
                    memorySwapBytes = null,
                    memoryReservationBytes = null,
                    nanoCpus = null,
                    cpuShares = null,
                    cpuQuota = null,
                    cpuPeriod = null,
                    cpusetCpus = null,
                    pidsLimit = null,
                    capAdd = emptyList(),
                    capDrop = emptyList(),
                    securityOptions = emptyList(),
                    tmpfs = emptyMap(),
                    sysctls = emptyMap()
                ),
                ports = emptyList(),
                labels = container.labels,
                env = emptyMap(),
                envNames = emptyList(),
                mounts = emptyList(),
                networks = emptyMap(),
                restartPolicy = DockerRestartPolicy(DockerRestartPolicyType.NO),
                restartCount = null,
                execIds = emptyList(),
                sizeRwBytes = null,
                sizeRootFsBytes = null
            )
        }

        private fun newFullContainerId(): String {
            return buildString {
                repeat(2) {
                    append(UUID.randomUUID().toString().replace("-", ""))
                }
            }
        }

        data class TestContainer(
            val id: String,
            val name: String,
            val labels: Map<String, String>
        )
    }
}
