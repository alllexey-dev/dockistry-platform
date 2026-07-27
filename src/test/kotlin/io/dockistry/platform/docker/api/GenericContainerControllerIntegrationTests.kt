package io.dockistry.platform.docker.api

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.auth.application.TokenManagementService
import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerContainerAlreadyStartedException
import io.dockistry.platform.docker.domain.model.DockerAgentAvailability
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

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
        val fixtures = IamProjectFixtures(entityManager)
        val user = authenticatedUser(fixtures, "container-manager")
        fixtures.grantSystemPermission(
            user.principal,
            fixtures.globalScope(),
            SystemPermissionNames.DOCKER_GENERIC_CONTAINERS_MANAGE
        )
        fixtures.flush()

        val containerId = UUID.randomUUID().toString()
        val endpoint = "/api/docker/containers/$containerId/start"

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
        private val startedContainerIds = mutableSetOf<String>()

        fun reset() {
            startedContainerIds.clear()
        }

        override fun status(): DockerAgentInfo {
            return DockerAgentInfo(state = DockerAgentAvailability.AVAILABLE)
        }

        override fun listImages(): List<DockerImageSummary> {
            error("Images are not used in this test")
        }

        override fun inspectImage(imageId: String): DockerImageInfo {
            error("Images are not used in this test")
        }

        override fun pullImage(imageRef: DockerImageRef) {
            error("Images are not used in this test")
        }

        override fun removeImage(imageId: String, force: Boolean) {
            error("Images are not used in this test")
        }

        override fun findNetworkByName(name: String): DockerNetworkRef? {
            error("Network lifecycle is not used in this test")
        }

        override fun createNetwork(
            name: String,
            labels: Map<String, String>
        ): DockerNetworkRef {
            error("Network lifecycle is not used in this test")
        }

        override fun removeNetwork(networkId: String) {
            error("Network lifecycle is not used in this test")
        }

        override fun createVolume(
            name: String,
            labels: Map<String, String>
        ) {
            error("Volume lifecycle is not used in this test")
        }

        override fun listVolumes(filter: DockerLabelsFilter): List<DockerVolumeRef> {
            error("Volume lifecycle is not used in this test")
        }

        override fun findVolumeByName(name: String): DockerVolumeRef? {
            error("Volume lifecycle is not used in this test")
        }

        override fun removeVolume(name: String) {
            error("Volume lifecycle is not used in this test")
        }

        override fun createContainer(command: DockerContainerCreateCommand): String {
            error("Container creation is not used in this test")
        }

        override fun listContainers(filter: DockerLabelsFilter): List<DockerContainerSummary> {
            return emptyList()
        }

        override fun inspectContainer(containerId: String): DockerContainerDetails {
            return containerDetails(containerId)
        }

        override fun getContainerStats(containerId: String): DockerContainerStats {
            error("Stats are not used in this test")
        }

        override fun getLogs(
            containerId: String,
            request: DockerContainerLogsRequest
        ): DockerContainerLogs {
            error("Logs are not used in this test")
        }

        override fun startContainer(containerId: String) {
            if (!startedContainerIds.add(containerId)) {
                throw DockerContainerAlreadyStartedException()
            }
        }

        override fun stopContainer(containerId: String) = Unit

        override fun restartContainer(containerId: String) = Unit

        override fun pauseContainer(containerId: String) = Unit

        override fun unpauseContainer(containerId: String) = Unit

        override fun deleteContainer(containerId: String) = Unit

        private fun containerDetails(containerId: String): DockerContainerDetails {
            return DockerContainerDetails(
                id = containerId,
                name = "test-container",
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
                    labels = emptyMap(),
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
                labels = emptyMap(),
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
    }
}
