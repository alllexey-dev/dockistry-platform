package io.dockistry.platform.services.application

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.DockerContainerNotFoundException
import io.dockistry.platform.docker.domain.DockerLabels
import io.dockistry.platform.docker.domain.DockerVolumeNotFoundException
import io.dockistry.platform.docker.domain.model.DockerAgentInfo
import io.dockistry.platform.docker.domain.model.DockerContainerCreateCommand
import io.dockistry.platform.docker.domain.model.DockerContainerDetails
import io.dockistry.platform.docker.domain.model.DockerContainerLogs
import io.dockistry.platform.docker.domain.model.DockerContainerLogsRequest
import io.dockistry.platform.docker.domain.model.DockerContainerState
import io.dockistry.platform.docker.domain.model.DockerContainerStats
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerImageInfo
import io.dockistry.platform.docker.domain.model.DockerImageRef
import io.dockistry.platform.docker.domain.model.DockerImageSummary
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.docker.domain.model.DockerNetworkRef
import io.dockistry.platform.docker.domain.model.DockerVolumeRef
import io.dockistry.platform.services.domain.DeploymentOperationInProgressException
import io.dockistry.platform.services.domain.ManagedServiceDeploymentCanceled
import io.dockistry.platform.services.domain.ManagedServiceDeploymentStatus
import io.dockistry.platform.services.domain.ServiceVolumeMount
import io.dockistry.platform.services.infrastructure.ManagedServiceDeploymentRepository
import io.dockistry.platform.services.testsupport.ManagedServiceFixtures
import io.dockistry.platform.services.testsupport.managedServiceDraft
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TIMEOUT_SECONDS = 15L

@SpringBootTest(
    properties = [
        "app.managed-services.deployment-execution-gate.lock-timeout=100ms"
    ]
)
@Testcontainers
@Import(
    TestcontainersConfig::class,
    TestClockConfig::class,
    DeploymentCleanupGateIntegrationTests.FakeDockerAgentClientConfig::class
)
class DeploymentCleanupGateIntegrationTests @Autowired constructor(
    private val dockerAgentClient: FakeDockerAgentClient,
    private val deployExecutor: ManagedServiceDeployExecutor,
    private val cleanupService: ManagedServiceDeploymentCleanupService,
    private val deploymentLifecycleService: ManagedServiceDeploymentLifecycleService,
    private val deploymentRepository: ManagedServiceDeploymentRepository,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager
) {
    private val newTransactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @BeforeEach
    fun resetDockerAgent() {
        dockerAgentClient.reset()
    }

    @Test
    fun `cleanup cannot pass container creation and executor removes late container`() {
        val context = createServiceContext()
        val createContainerEntered = CountDownLatch(1)
        val containerCreationAllowed = CountDownLatch(1)
        val afterCreateContainer = CountDownLatch(1)
        val afterCreateContainerContinueAllowed = CountDownLatch(1)
        dockerAgentClient.createContainerEntered = createContainerEntered
        dockerAgentClient.containerCreationAllowed = containerCreationAllowed
        dockerAgentClient.afterCreateContainer = afterCreateContainer
        dockerAgentClient.afterCreateContainerContinueAllowed =
            afterCreateContainerContinueAllowed

        val pool = Executors.newSingleThreadExecutor()
        val deployment = pool.submit<Result<Unit>> {
            runCatching { deployExecutor.deploy(context.serviceId) }
        }

        try {
            await(createContainerEntered, "Container creation wasn't entered")
            val deploymentId = requestCancellation(context.serviceId)

            assertCleanupBlocked(context, deploymentId)
            containerCreationAllowed.countDown()
            await(afterCreateContainer, "Container wasn't created")

            val createdContainer = dockerAgentClient.createdContainerCommands
                .entries
                .single { (_, command) ->
                    command.labels[DockerLabels.DEPLOYMENT_ID] ==
                            deploymentId.toString()
                }
            assertTrue(
                dockerAgentClient.activeContainerIds.contains(createdContainer.key),
                "Created deployment container wasn't observable before cleanup"
            )

            afterCreateContainerContinueAllowed.countDown()
            assertFailsWith<ManagedServiceDeploymentCanceled> {
                deployment.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getOrThrow()
            }

            assertFalse(
                dockerAgentClient.activeContainerIds.contains(createdContainer.key),
                "Canceled deployment container wasn't removed"
            )
            assertTrue(
                dockerAgentClient.deletedContainerIds.contains(createdContainer.key),
                "Canceled deployment container deletion wasn't observed"
            )
            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.CANCELED
            )
        } finally {
            containerCreationAllowed.countDown()
            afterCreateContainerContinueAllowed.countDown()
            terminate(pool, "Container deployment executor didn't terminate")
        }
    }

    @Test
    fun `cleanup cannot pass volume creation and executor removes late volume`() {
        val volumeName = randomResourceName("data")
        val context = createServiceContext(
            volume = ServiceVolumeMount(
                name = volumeName,
                mountPath = "/data/$volumeName"
            )
        )
        val createVolumeEntered = CountDownLatch(1)
        val volumeCreationAllowed = CountDownLatch(1)
        val afterCreateVolume = CountDownLatch(1)
        val afterCreateVolumeContinueAllowed = CountDownLatch(1)
        dockerAgentClient.createVolumeEntered = createVolumeEntered
        dockerAgentClient.volumeCreationAllowed = volumeCreationAllowed
        dockerAgentClient.afterCreateVolume = afterCreateVolume
        dockerAgentClient.afterCreateVolumeContinueAllowed =
            afterCreateVolumeContinueAllowed

        val pool = Executors.newSingleThreadExecutor()
        val deployment = pool.submit<Result<Unit>> {
            runCatching { deployExecutor.deploy(context.serviceId) }
        }

        try {
            await(createVolumeEntered, "Volume creation wasn't entered")
            val deploymentId = requestCancellation(context.serviceId)

            assertCleanupBlocked(context, deploymentId)
            volumeCreationAllowed.countDown()
            await(afterCreateVolume, "Volume wasn't created")

            val createdVolume = dockerAgentClient.createdVolumes.values.single {
                it.labels[DockerLabels.DEPLOYMENT_ID] == deploymentId.toString()
            }
            assertTrue(
                dockerAgentClient.activeVolumeNames.contains(createdVolume.name),
                "Created deployment volume wasn't observable before cleanup"
            )

            afterCreateVolumeContinueAllowed.countDown()
            assertFailsWith<ManagedServiceDeploymentCanceled> {
                deployment.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getOrThrow()
            }

            assertFalse(
                dockerAgentClient.activeVolumeNames.contains(createdVolume.name),
                "Canceled deployment volume wasn't removed"
            )
            assertTrue(
                dockerAgentClient.removedVolumeNames.contains(createdVolume.name),
                "Canceled deployment volume removal wasn't observed"
            )
            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.CANCELED
            )
        } finally {
            volumeCreationAllowed.countDown()
            afterCreateVolumeContinueAllowed.countDown()
            terminate(pool, "Volume deployment executor didn't terminate")
        }
    }

    @Test
    fun `cleanup cannot pass network creation and cancellation completes afterwards`() {
        val context = createServiceContext()
        val createNetworkEntered = CountDownLatch(1)
        val networkCreationAllowed = CountDownLatch(1)
        val afterCreateNetwork = CountDownLatch(1)
        val afterCreateNetworkContinueAllowed = CountDownLatch(1)
        dockerAgentClient.createNetworkEntered = createNetworkEntered
        dockerAgentClient.networkCreationAllowed = networkCreationAllowed
        dockerAgentClient.afterCreateNetwork = afterCreateNetwork
        dockerAgentClient.afterCreateNetworkContinueAllowed =
            afterCreateNetworkContinueAllowed

        val pool = Executors.newSingleThreadExecutor()
        val deployment = pool.submit<Result<Unit>> {
            runCatching { deployExecutor.deploy(context.serviceId) }
        }

        try {
            await(createNetworkEntered, "Network creation wasn't entered")
            val deploymentId = requestCancellation(context.serviceId)

            assertCleanupBlocked(context, deploymentId)
            networkCreationAllowed.countDown()
            await(afterCreateNetwork, "Network wasn't created")

            val createdNetwork = dockerAgentClient.createdNetworks.values.single {
                it.labels[DockerLabels.PROJECT_ID] == context.projectId.toString()
            }
            assertEquals(
                createdNetwork,
                dockerAgentClient.networksByName[createdNetwork.name],
                "Created project network wasn't observable before cancellation continued"
            )

            afterCreateNetworkContinueAllowed.countDown()
            assertFailsWith<ManagedServiceDeploymentCanceled> {
                deployment.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getOrThrow()
            }

            assertEquals(
                createdNetwork,
                dockerAgentClient.networksByName[createdNetwork.name],
                "Shared project network should remain available after cancellation"
            )
            assertDeploymentStatus(
                deploymentId,
                ManagedServiceDeploymentStatus.CANCELED
            )
        } finally {
            networkCreationAllowed.countDown()
            afterCreateNetworkContinueAllowed.countDown()
            terminate(pool, "Network deployment executor didn't terminate")
        }
    }

    private fun createServiceContext(
        volume: ServiceVolumeMount? = null
    ): ServiceContext = inNewTransaction {
        val suffix = randomResourceName("gate")
        val project = IamProjectFixtures(entityManager).project(
            name = "Gate Project $suffix",
            slug = "gate-project-$suffix"
        )
        val service = ManagedServiceFixtures(entityManager).service(
            project = project,
            name = "gate-service-$suffix",
            draft = managedServiceDraft(
                imageRef = "nginx:$suffix",
                volumes = listOfNotNull(volume)
            )
        )
        entityManager.flush()

        ServiceContext(
            projectId = project.id,
            serviceId = service.id
        )
    }

    private fun requestCancellation(serviceId: UUID): UUID {
        val deployment = deploymentRepository.findActiveByServiceId(serviceId).single()
        assertEquals(
            ManagedServiceDeploymentCancellationResult.Requested,
            deploymentLifecycleService.requestDeploymentCancellationForDeploymentId(
                deployment.id
            )
        )
        return deployment.id
    }

    private fun assertCleanupBlocked(
        context: ServiceContext,
        deploymentId: UUID
    ) {
        assertFailsWith<DeploymentOperationInProgressException> {
            cleanupService.cleanup(
                projectId = context.projectId,
                serviceId = context.serviceId,
                deploymentId = deploymentId
            )
        }
        assertDeploymentStatus(
            deploymentId,
            ManagedServiceDeploymentStatus.CANCELING
        )
    }

    private fun assertDeploymentStatus(
        deploymentId: UUID,
        expected: ManagedServiceDeploymentStatus
    ) {
        assertEquals(
            expected,
            deploymentRepository.findById(deploymentId).orElseThrow().status
        )
    }

    private fun <T : Any> inNewTransaction(block: () -> T): T {
        return newTransactionTemplate.execute { block() }
    }

    private fun await(latch: CountDownLatch, failureMessage: String) {
        assertTrue(
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            failureMessage
        )
    }

    private fun terminate(
        pool: ExecutorService,
        failureMessage: String
    ) {
        pool.shutdownNow()
        assertTrue(
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            failureMessage
        )
    }

    private fun randomResourceName(prefix: String): String =
        "$prefix-${UUID.randomUUID().toString().replace("-", "").take(8)}"

    private data class ServiceContext(
        val projectId: UUID,
        val serviceId: UUID
    )

    @TestConfiguration
    class FakeDockerAgentClientConfig {
        @Bean
        @Primary
        fun fakeDockerAgentClient() = FakeDockerAgentClient()
    }

    class FakeDockerAgentClient : DockerAgentClient {
        val pulledImages = ConcurrentHashMap.newKeySet<DockerImageRef>()
        val createdNetworks = ConcurrentHashMap<String, DockerNetworkRef>()
        val networksByName = ConcurrentHashMap<String, DockerNetworkRef>()
        val createdVolumes = ConcurrentHashMap<String, DockerVolumeRef>()
        val activeVolumeNames = ConcurrentHashMap.newKeySet<String>()
        val removedVolumeNames = ConcurrentHashMap.newKeySet<String>()
        val createdContainerCommands =
            ConcurrentHashMap<String, DockerContainerCreateCommand>()
        val activeContainerIds = ConcurrentHashMap.newKeySet<String>()
        val deletedContainerIds = ConcurrentHashMap.newKeySet<String>()
        private val containerStates =
            ConcurrentHashMap<String, DockerContainerState>()

        @Volatile
        var createContainerEntered: CountDownLatch? = null

        @Volatile
        var containerCreationAllowed: CountDownLatch? = null

        @Volatile
        var afterCreateContainer: CountDownLatch? = null

        @Volatile
        var afterCreateContainerContinueAllowed: CountDownLatch? = null

        @Volatile
        var createVolumeEntered: CountDownLatch? = null

        @Volatile
        var volumeCreationAllowed: CountDownLatch? = null

        @Volatile
        var afterCreateVolume: CountDownLatch? = null

        @Volatile
        var afterCreateVolumeContinueAllowed: CountDownLatch? = null

        @Volatile
        var createNetworkEntered: CountDownLatch? = null

        @Volatile
        var networkCreationAllowed: CountDownLatch? = null

        @Volatile
        var afterCreateNetwork: CountDownLatch? = null

        @Volatile
        var afterCreateNetworkContinueAllowed: CountDownLatch? = null

        fun reset() {
            pulledImages.clear()
            createdNetworks.clear()
            networksByName.clear()
            createdVolumes.clear()
            activeVolumeNames.clear()
            removedVolumeNames.clear()
            createdContainerCommands.clear()
            activeContainerIds.clear()
            deletedContainerIds.clear()
            containerStates.clear()
            createContainerEntered = null
            containerCreationAllowed = null
            afterCreateContainer = null
            afterCreateContainerContinueAllowed = null
            createVolumeEntered = null
            volumeCreationAllowed = null
            afterCreateVolume = null
            afterCreateVolumeContinueAllowed = null
            createNetworkEntered = null
            networkCreationAllowed = null
            afterCreateNetwork = null
            afterCreateNetworkContinueAllowed = null
        }

        override fun status(): DockerAgentInfo {
            error("Docker agent status flow not used in this test")
        }

        override fun listImages(): List<DockerImageSummary> {
            error("Docker image listing flow not used in this test")
        }

        override fun inspectImage(imageId: String): DockerImageInfo {
            error("Docker image inspection flow not used in this test")
        }

        override fun pullImage(imageRef: DockerImageRef) {
            pulledImages.add(imageRef)
        }

        override fun removeImage(imageId: String, force: Boolean) {
            error("Docker image removal flow not used in this test")
        }

        override fun findNetworkByName(name: String): DockerNetworkRef? {
            return networksByName[name]
        }

        override fun createNetwork(
            name: String,
            labels: Map<String, String>
        ): DockerNetworkRef {
            createNetworkEntered?.countDown()
            awaitPermission(
                networkCreationAllowed,
                "Network creation allowed latch wasn't released"
            )

            val network = DockerNetworkRef(
                id = UUID.randomUUID().toString(),
                name = name,
                labels = labels.toMap()
            )
            createdNetworks[network.id] = network
            networksByName[name] = network
            afterCreateNetwork?.countDown()
            awaitPermission(
                afterCreateNetworkContinueAllowed,
                "Network creation continuation latch wasn't released"
            )
            return network
        }

        override fun removeNetwork(networkId: String) {
            error("Docker network removal flow not used in this test")
        }

        override fun createVolume(
            name: String,
            labels: Map<String, String>
        ) {
            createVolumeEntered?.countDown()
            awaitPermission(
                volumeCreationAllowed,
                "Volume creation allowed latch wasn't released"
            )

            val volume = DockerVolumeRef(
                name = name,
                labels = labels.toMap()
            )
            createdVolumes[name] = volume
            activeVolumeNames.add(name)
            afterCreateVolume?.countDown()
            awaitPermission(
                afterCreateVolumeContinueAllowed,
                "Volume creation continuation latch wasn't released"
            )
        }

        override fun findVolumeByName(name: String): DockerVolumeRef? {
            if (!activeVolumeNames.contains(name)) {
                return null
            }
            return createdVolumes[name]
        }

        override fun listVolumes(filter: DockerLabelsFilter): List<DockerVolumeRef> {
            return activeVolumeNames.mapNotNull { name ->
                createdVolumes[name]?.takeIf { it.labels.matches(filter) }
            }
        }

        override fun removeVolume(name: String) {
            if (!activeVolumeNames.remove(name)) {
                throw DockerVolumeNotFoundException("Docker volume $name wasn't found")
            }
            removedVolumeNames.add(name)
        }

        override fun createContainer(command: DockerContainerCreateCommand): String {
            createContainerEntered?.countDown()
            awaitPermission(
                containerCreationAllowed,
                "Container creation allowed latch wasn't released"
            )

            val containerId = UUID.randomUUID().toString()
            createdContainerCommands[containerId] = command.copy(
                env = command.env.toMap(),
                labels = command.labels.toMap(),
                command = command.command?.toList(),
                args = command.args?.toList(),
                networkAliases = command.networkAliases.toList(),
                portBindings = command.portBindings.toList(),
                volumeBindings = command.volumeBindings.toList()
            )
            activeContainerIds.add(containerId)
            containerStates[containerId] = DockerContainerState.CREATED
            afterCreateContainer?.countDown()
            awaitPermission(
                afterCreateContainerContinueAllowed,
                "Container creation continuation latch wasn't released"
            )
            return containerId
        }

        override fun listContainers(
            filter: DockerLabelsFilter
        ): List<DockerContainerSummary> {
            return activeContainerIds.mapNotNull { containerId ->
                val command = createdContainerCommands[containerId]
                    ?: return@mapNotNull null
                if (!command.labels.matches(filter)) {
                    return@mapNotNull null
                }

                DockerContainerSummary(
                    id = containerId,
                    name = command.name,
                    names = listOf(command.name),
                    image = "${command.image.repository}:${command.image.tag}",
                    imageId = null,
                    command = command.command?.joinToString(" "),
                    createdEpochSeconds = null,
                    state = containerStates[containerId]
                        ?: DockerContainerState.UNKNOWN,
                    status = null,
                    ports = emptyList(),
                    labels = command.labels,
                    sizeRwBytes = null,
                    sizeRootFsBytes = null,
                    networkMode = command.networkName,
                    networks = emptyMap(),
                    mounts = emptyList()
                )
            }
        }

        override fun inspectContainer(containerId: String): DockerContainerDetails {
            error("Docker container inspection flow not used in this test")
        }

        override fun getContainerStats(containerId: String): DockerContainerStats {
            error("Docker container stats flow not used in this test")
        }

        override fun getLogs(
            containerId: String,
            request: DockerContainerLogsRequest
        ): DockerContainerLogs {
            error("Docker container logs flow not used in this test")
        }

        override fun startContainer(containerId: String) {
            if (!activeContainerIds.contains(containerId)) {
                throw DockerContainerNotFoundException(
                    "Docker container $containerId wasn't found"
                )
            }
            containerStates[containerId] = DockerContainerState.RUNNING
        }

        override fun stopContainer(containerId: String) {
            error("Docker container stop flow not used in this test")
        }

        override fun restartContainer(containerId: String) {
            error("Docker container restart flow not used in this test")
        }

        override fun pauseContainer(containerId: String) {
            error("Docker container pause flow not used in this test")
        }

        override fun unpauseContainer(containerId: String) {
            error("Docker container unpause flow not used in this test")
        }

        override fun deleteContainer(containerId: String) {
            if (!activeContainerIds.remove(containerId)) {
                throw DockerContainerNotFoundException(
                    "Docker container $containerId wasn't found"
                )
            }
            containerStates.remove(containerId)
            deletedContainerIds.add(containerId)
        }

        private fun awaitPermission(
            latch: CountDownLatch?,
            failureMessage: String
        ) {
            if (latch == null) {
                return
            }
            assertTrue(
                latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                failureMessage
            )
        }

        private fun Map<String, String>.matches(
            filter: DockerLabelsFilter
        ): Boolean {
            return filter.labels.all { (name, value) -> this[name] == value }
        }
    }
}
