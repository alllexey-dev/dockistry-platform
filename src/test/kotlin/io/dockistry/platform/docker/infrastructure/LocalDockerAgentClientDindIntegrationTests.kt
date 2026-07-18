package io.dockistry.platform.docker.infrastructure

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.exception.NotFoundException as DockerNotFoundException
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Mount
import com.github.dockerjava.api.model.MountType
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import com.github.dockerjava.api.model.RestartPolicy
import com.github.dockerjava.api.model.Volume
import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerContainerAlreadyPausedException
import io.dockistry.platform.docker.domain.DockerContainerAlreadyStartedException
import io.dockistry.platform.docker.domain.DockerContainerAlreadyStoppedException
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.DockerContainerNotPausedException
import io.dockistry.platform.docker.domain.DockerContainerNotRunningException
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.model.DockerAgentAvailability
import io.dockistry.platform.docker.domain.model.DockerContainerState
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType
import io.dockistry.platform.docker.testsupport.DindDockerJavaClientTestConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringJUnitConfig(
    classes = [
        LocalDockerAgentClient::class,
        DindDockerJavaClientTestConfig::class
    ]
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalDockerAgentClientDindIntegrationTests {
    @Autowired
    private lateinit var dockerAgentClient: DockerAgentClient

    @Autowired
    private lateinit var dockerClient: DockerClient

    private val pulledImages = mutableSetOf<String>()
    private val createdVolumes = mutableSetOf<String>()

    @BeforeEach
    fun cleanBeforeTest() {
        cleanDockerEngine()
    }

    @AfterEach
    fun cleanAfterTest() {
        cleanDockerEngine()
    }

    @Test
    fun `status returns available docker engine details from dind`() {
        val status = dockerAgentClient.status()

        assertEquals(DockerAgentAvailability.AVAILABLE, status.state)
        assertNotBlank(status.engineVersion)
        assertNotBlank(status.apiVersion)
        assertNotBlank(status.operatingSystem)
        assertNotBlank(status.architecture)
        assertNotBlank(status.serverName)
        assertNotBlank(status.dockerRootDir)
        assertNotBlank(status.cgroupDriver)
        assertNotBlank(status.cgroupVersion)
        assertNull(status.errorMessage)
    }

    @Test
    fun `list containers returns empty list for project without managed containers`() {
        val projectId = UUID.randomUUID()
        val otherProjectId = UUID.randomUUID()

        assertTrue(dockerAgentClient.listContainers(projectFilter(projectId)).isEmpty())

        createContainer(
            image = BUSYBOX_IMAGE,
            name = containerName("other-project"),
            labels = managedLabels(otherProjectId),
            command = keepAliveCommand()
        ).start()

        assertTrue(dockerAgentClient.listContainers(projectFilter(projectId)).isEmpty())
        assertEquals(1, dockerAgentClient.listContainers(projectFilter(otherProjectId)).size)
    }

    @Test
    fun `exists volume by name uses Docker name filter`() {
        val volumeName = createVolume("exists-volume")

        assertTrue(dockerAgentClient.existsVolumeByName(volumeName))
        assertFalse(dockerAgentClient.existsVolumeByName("$volumeName-missing"))
    }

    @Test
    fun `list containers returns matching project containers with ports mounts and states`() {
        val projectId = UUID.randomUUID()
        val volumeName = createVolume("list-volume")
        val bindSource = "/tmp/${containerName("bind-source")}"
        val alpineName = containerName("alpine-list")
        val busyboxName = containerName("busybox-list")

        val alpine = createContainer(
            image = ALPINE_IMAGE,
            name = alpineName,
            labels = managedLabels(projectId) + ("role" to "api"),
            command = keepAliveCommand(),
            exposedPorts = listOf(ExposedPort.tcp(8080)),
            hostConfig = HostConfig.newHostConfig()
                .withPortBindings(
                    PortBinding(Ports.Binding.bindPort(18080), ExposedPort.tcp(8080))
                )
                .withBinds(Bind.parse("$bindSource:/mnt/bind:ro"))
                .withMounts(
                    listOf(
                        Mount()
                            .withType(MountType.VOLUME)
                            .withSource(volumeName)
                            .withTarget("/mnt/volume"),
                        Mount()
                            .withType(MountType.TMPFS)
                            .withTarget("/mnt/tmpfs")
                    )
                )
        ).start()
        val busybox = createContainer(
            image = BUSYBOX_IMAGE,
            name = busyboxName,
            labels = managedLabels(projectId) + ("role" to "worker"),
            command = keepAliveCommand(),
            exposedPorts = listOf(ExposedPort.udp(5353)),
            hostConfig = HostConfig.newHostConfig()
                .withPortBindings(
                    PortBinding(Ports.Binding.bindPort(15353), ExposedPort.udp(5353))
                )
        ).start()

        dockerClient.pauseContainerCmd(busybox.id).exec()

        eventuallyAssert {
            val containers = dockerAgentClient.listContainers(projectFilter(projectId))
            assertEquals(2, containers.size)

            val alpineSummary = containers.single { it.name == alpineName }
            val busyboxSummary = containers.single { it.name == busyboxName }

            assertEquals(DockerContainerState.RUNNING, alpineSummary.state)
            assertEquals(DockerContainerState.PAUSED, busyboxSummary.state)
            assertEquals(ALPINE_IMAGE, alpineSummary.image)
            assertEquals(BUSYBOX_IMAGE, busyboxSummary.image)
            assertEquals("api", alpineSummary.labels["role"])
            assertEquals("worker", busyboxSummary.labels["role"])
            assertHasPort(alpineSummary, privatePort = 8080, type = "tcp")
            assertHasPort(busyboxSummary, privatePort = 5353, type = "udp")
            assertHasMount(alpineSummary, type = "bind", target = "/mnt/bind")
            assertHasMount(alpineSummary, type = "volume", target = "/mnt/volume")
            assertHasMount(alpineSummary, type = "tmpfs", target = "/mnt/tmpfs")
        }
    }

    @Test
    fun `inspect container returns summary and details for configured container`() {
        val projectId = UUID.randomUUID()
        val volumeName = createVolume("inspect-volume")
        val bindSource = "/tmp/${containerName("inspect-bind")}"
        val name = containerName("inspect")

        val container = createContainer(
            image = ALPINE_IMAGE,
            name = name,
            labels = managedLabels(projectId) + ("component" to "inspect"),
            command = keepAliveCommand(),
            env = listOf("VISIBLE_ENV=visible-value", "EMPTY_ENV="),
            user = "1000:1000",
            workingDir = "/tmp",
            hostName = "dockistry-host",
            exposedPorts = listOf(ExposedPort.tcp(8081)),
            hostConfig = HostConfig.newHostConfig()
                .withPortBindings(
                    PortBinding(Ports.Binding.bindPort(18081), ExposedPort.tcp(8081))
                )
                .withMemory(64L * 1024L * 1024L)
                .withMemoryReservation(32L * 1024L * 1024L)
                .withMemorySwap(128L * 1024L * 1024L)
                .withNanoCPUs(100_000_000L)
                .withCpuShares(256)
                .withCpusetCpus("0")
                .withPidsLimit(64)
                .withReadonlyRootfs(true)
                .withRestartPolicy(RestartPolicy.onFailureRestart(3))
                .withBinds(Bind.parse("$bindSource:/mnt/bind:ro"))
                .withMounts(
                    listOf(
                        Mount()
                            .withType(MountType.VOLUME)
                            .withSource(volumeName)
                            .withTarget("/mnt/volume"),
                        Mount()
                            .withType(MountType.TMPFS)
                            .withTarget("/tmp")
                    )
                )
        ).start()

        eventuallyAssert {
            val summary = dockerAgentClient.listContainers(projectFilter(projectId))
                .single { it.id == container.id }
            assertEquals(name, summary.name)
            assertEquals(ALPINE_IMAGE, summary.image)
            assertEquals(DockerContainerState.RUNNING, summary.state)
            assertHasPort(summary, privatePort = 8081, type = "tcp")
        }

        val details = dockerAgentClient.inspectContainer(container.id)

        assertEquals(container.id, details.id)
        assertEquals(name, details.name)
        assertEquals(ALPINE_IMAGE, details.image)
        assertEquals(DockerContainerState.RUNNING, details.state.state)
        assertEquals(true, details.state.running)
        assertEquals(false, details.state.paused)
        assertEquals("inspect", details.labels["component"])
        assertEquals("visible-value", details.env["VISIBLE_ENV"])
        assertEquals("", details.env["EMPTY_ENV"])
        assertTrue(details.envNames.containsAll(listOf("EMPTY_ENV", "VISIBLE_ENV")))
        assertEquals("/tmp", details.config.workingDir)
        assertEquals("1000:1000", details.config.user)
        assertEquals("dockistry-host", details.config.hostname)
        assertEquals(64L * 1024L * 1024L, details.hostConfig.memoryBytes)
        assertEquals(32L * 1024L * 1024L, details.hostConfig.memoryReservationBytes)
        assertEquals(128L * 1024L * 1024L, details.hostConfig.memorySwapBytes)
        assertEquals(100_000_000L, details.hostConfig.nanoCpus)
        assertEquals(256, details.hostConfig.cpuShares)
        assertEquals("0", details.hostConfig.cpusetCpus)
        assertEquals(64L, details.hostConfig.pidsLimit)
        assertEquals(true, details.hostConfig.readonlyRootfs)
        assertEquals(DockerRestartPolicyType.ON_FAILURE, details.restartPolicy.type)
        assertEquals(3, details.restartPolicy.maxRetries)
        assertTrue(details.networks.containsKey("bridge"))
        assertHasPort(details.ports, privatePort = 8081, type = "tcp")
        assertHasMount(details.mounts, type = "bind", target = "/mnt/bind")
        assertHasMount(details.mounts, type = "volume", target = "/mnt/volume")
        assertHasMount(details.mounts, type = "tmpfs", target = "/tmp")
    }

    @Test
    fun `lifecycle actions move container through expected states and delete it`() {
        val projectId = UUID.randomUUID()
        val container = createContainer(
            image = BUSYBOX_IMAGE,
            name = containerName("lifecycle"),
            labels = managedLabels(projectId),
            command = keepAliveCommand()
        ).start()

        assertContainerState(container.id, DockerContainerState.RUNNING)

        dockerAgentClient.stopContainer(container.id)
        assertContainerState(container.id, DockerContainerState.EXITED)

        dockerAgentClient.startContainer(container.id)
        assertContainerState(container.id, DockerContainerState.RUNNING)

        dockerAgentClient.pauseContainer(container.id)
        assertContainerState(container.id, DockerContainerState.PAUSED)

        dockerAgentClient.unpauseContainer(container.id)
        assertContainerState(container.id, DockerContainerState.RUNNING)

        dockerAgentClient.restartContainer(container.id)
        assertContainerState(container.id, DockerContainerState.RUNNING)

        dockerAgentClient.deleteContainer(container.id)
        eventuallyAssert {
            assertFailsWith<DockerContainerNotFoundException> {
                dockerAgentClient.inspectContainer(container.id)
            }
        }
    }

    @Test
    fun `lifecycle no op actions throw explicit state exceptions`() {
        val projectId = UUID.randomUUID()
        val container = createContainer(
            image = BUSYBOX_IMAGE,
            name = containerName("lifecycle-noop"),
            labels = managedLabels(projectId),
            command = keepAliveCommand()
        ).start()

        assertContainerState(container.id, DockerContainerState.RUNNING)

        assertFailsWith<DockerContainerAlreadyStartedException> {
            dockerAgentClient.startContainer(container.id)
        }

        dockerAgentClient.pauseContainer(container.id)
        assertContainerState(container.id, DockerContainerState.PAUSED)
        assertFailsWith<DockerContainerAlreadyPausedException> {
            dockerAgentClient.pauseContainer(container.id)
        }

        dockerAgentClient.unpauseContainer(container.id)
        assertContainerState(container.id, DockerContainerState.RUNNING)
        assertFailsWith<DockerContainerNotPausedException> {
            dockerAgentClient.unpauseContainer(container.id)
        }

        dockerAgentClient.stopContainer(container.id)
        assertContainerState(container.id, DockerContainerState.EXITED)
        assertFailsWith<DockerContainerAlreadyStoppedException> {
            dockerAgentClient.stopContainer(container.id)
        }
        assertFailsWith<DockerContainerNotRunningException> {
            dockerAgentClient.pauseContainer(container.id)
        }
    }

    @Tag("docker-stats")
    @Test
    fun `stats returns cpu network and disk activity for running container`() {
        val projectId = UUID.randomUUID()
        val server = createContainer(
            image = BUSYBOX_IMAGE,
            name = containerName("stats-server"),
            labels = mapOf("dockistry-test-role" to "stats-server"),
            command = listOf(
                "sh",
                "-c",
                "mkdir -p /www && dd if=/dev/zero of=/www/index.html bs=1024 count=64 " +
                    "2>/dev/null && httpd -f -p 8080 -h /www"
            )
        ).start()
        val serverIp = eventuallyValue {
            dockerClient.inspectContainerCmd(server.id)
                .exec()
                .networkSettings
                ?.networks
                ?.get("bridge")
                ?.ipAddress
                ?.takeIf { it.isNotBlank() }
        }
        val workload = createContainer(
            image = BUSYBOX_IMAGE,
            name = containerName("stats-workload"),
            labels = managedLabels(projectId),
            command = listOf(
                "sh",
                "-c",
                """
                i=0
                while [ "${'$'}i" -lt 100 ]; do
                  wget -q -O /tmp/index-"${'$'}i".html http://$serverIp:8080/index.html || true
                  i="${'$'}((i + 1))"
                done
                dd if=/dev/zero of=/tmp/disk.bin bs=1024 count=1024 2>/dev/null
                while true; do :; done
                """.trimIndent()
            )
        ).start()

        val stats = eventuallyValue(timeout = Duration.ofSeconds(20)) {
            val current = dockerAgentClient.getContainerStats(workload.id)
            val cpuSeen = (current.cpuPercent ?: 0.0) > 0.0
            val networkSeen = (current.networkRxBytes ?: 0L) + (current.networkTxBytes ?: 0L) > 0L
            val diskSeen = (current.diskWriteBytes ?: 0L) > 0L

            current.takeIf { cpuSeen && networkSeen && diskSeen }
        }

        assertTrue(stats.cpuPercent!! > 0.0)
        assertTrue((stats.networkRxBytes ?: 0L) + (stats.networkTxBytes ?: 0L) > 0L)
        assertTrue((stats.diskWriteBytes ?: 0L) > 0L)
        assertNotNull(stats.memoryUsageBytes)
        assertNotNull(stats.memoryLimitBytes)
        assertEquals(1, dockerAgentClient.listContainers(projectFilter(projectId)).size)
    }

    private fun createContainer(
        image: String,
        name: String,
        labels: Map<String, String>,
        command: List<String>,
        env: List<String> = emptyList(),
        user: String? = null,
        workingDir: String? = null,
        hostName: String? = null,
        exposedPorts: List<ExposedPort> = emptyList(),
        hostConfig: HostConfig = HostConfig.newHostConfig()
    ): CreatedContainer {
        pullImage(image)

        val createCommand = dockerClient.createContainerCmd(image)
            .withName(name)
            .withLabels(labels)
            .withCmd(command)
            .withHostConfig(hostConfig)

        if (env.isNotEmpty()) {
            createCommand.withEnv(env)
        }
        if (user != null) {
            createCommand.withUser(user)
        }
        if (workingDir != null) {
            createCommand.withWorkingDir(workingDir)
        }
        if (hostName != null) {
            createCommand.withHostName(hostName)
        }
        if (exposedPorts.isNotEmpty()) {
            createCommand.withExposedPorts(exposedPorts)
        }

        return CreatedContainer(createCommand.exec().id)
    }

    private fun CreatedContainer.start(): CreatedContainer {
        dockerClient.startContainerCmd(id).exec()
        return this
    }

    private fun createVolume(namePrefix: String): String {
        val name = containerName(namePrefix)
        dockerClient.createVolumeCmd()
            .withName(name)
            .exec()
        createdVolumes += name

        return name
    }

    private fun pullImage(image: String) {
        if (image in pulledImages) {
            return
        }

        val imageParts = image.split(":", limit = 2)
        val completed = dockerClient.pullImageCmd(imageParts[0])
            .withTag(imageParts.getOrElse(1) { "latest" })
            .start()
            .awaitCompletion(IMAGE_PULL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertTrue(completed, "Timed out pulling Docker image $image")
        pulledImages += image
    }

    private fun cleanDockerEngine() {
        dockerClient.listContainersCmd()
            .withShowAll(true)
            .exec()
            .forEach { container ->
                runCatching {
                    dockerClient.removeContainerCmd(container.id)
                        .withForce(true)
                        .withRemoveVolumes(true)
                        .exec()
                }.getOrElse { exception ->
                    if (exception !is DockerNotFoundException) {
                        throw exception
                    }
                }
            }

        createdVolumes.forEach { volumeName ->
            runCatching {
                dockerClient.removeVolumeCmd(volumeName).exec()
            }.getOrElse { exception ->
                if (exception !is DockerNotFoundException) {
                    throw exception
                }
            }
        }
        createdVolumes.clear()
    }

    private fun projectFilter(projectId: UUID): DockerLabelsFilter {
        return DockerLabelsFilter(labels = managedLabels(projectId))
    }

    private fun managedLabels(projectId: UUID): Map<String, String> {
        return DockerLabels.forProject(projectId)
    }

    private fun keepAliveCommand(): List<String> {
        return listOf("sh", "-c", "while true; do sleep 1; done")
    }

    private fun containerName(prefix: String): String {
        return "dockistry-$prefix-${UUID.randomUUID().toString().take(8)}"
    }

    private fun assertNotBlank(value: String?) {
        assertTrue(!value.isNullOrBlank(), "Expected non-blank value")
    }

    private fun assertHasPort(
        container: DockerContainerSummary,
        privatePort: Int,
        type: String
    ) {
        assertHasPort(container.ports, privatePort, type)
    }

    private fun assertHasPort(
        ports: List<io.dockistry.platform.docker.domain.model.DockerPortInfo>,
        privatePort: Int,
        type: String
    ) {
        val matchingPort = ports.firstOrNull {
            it.privatePort == privatePort && it.type.equals(type, ignoreCase = true)
        }

        assertNotNull(matchingPort, "Expected $type/$privatePort port, actual ports: $ports")
        assertNotNull(matchingPort.publicPort, "Expected Docker to publish $type/$privatePort")
    }

    private fun assertHasMount(
        container: DockerContainerSummary,
        type: String,
        target: String
    ) {
        assertHasMount(container.mounts, type, target)
    }

    private fun assertHasMount(
        mounts: List<io.dockistry.platform.docker.domain.model.DockerMountInfo>,
        type: String,
        target: String
    ) {
        assertTrue(
            mounts.any { it.type == type && it.target == target },
            "Expected $type mount at $target, actual mounts: $mounts"
        )
    }

    private fun assertContainerState(containerId: String, state: DockerContainerState) {
        eventuallyAssert {
            assertEquals(state, dockerAgentClient.inspectContainer(containerId).state.state)
        }
    }

    private fun eventuallyAssert(
        timeout: Duration = Duration.ofSeconds(10),
        assertion: () -> Unit
    ) {
        eventuallyValue(timeout) {
            assertion()
            true
        }
    }

    private fun <T : Any> eventuallyValue(
        timeout: Duration = Duration.ofSeconds(10),
        supplier: () -> T?
    ): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        var lastFailure: Throwable? = null

        while (System.nanoTime() < deadline) {
            try {
                val value = supplier()
                if (value != null) {
                    return value
                }
            } catch (exception: AssertionError) {
                lastFailure = exception
            } catch (exception: RuntimeException) {
                lastFailure = exception
            }

            Thread.sleep(250)
        }

        throw AssertionError("Condition was not met within $timeout", lastFailure)
    }

    private data class CreatedContainer(val id: String)

    private companion object {
        const val ALPINE_IMAGE = "alpine:3.20"
        const val BUSYBOX_IMAGE = "busybox:1.36"
        const val IMAGE_PULL_TIMEOUT_SECONDS = 120L
    }
}
