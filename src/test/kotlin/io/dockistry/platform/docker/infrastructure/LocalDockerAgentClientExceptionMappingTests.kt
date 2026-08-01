package io.dockistry.platform.docker.infrastructure

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.ListVolumesCmd
import com.github.dockerjava.api.command.ListVolumesResponse
import com.github.dockerjava.api.command.PullImageCmd
import com.github.dockerjava.api.command.RemoveContainerCmd
import com.github.dockerjava.api.command.RemoveNetworkCmd
import com.github.dockerjava.api.command.RemoveVolumeCmd
import com.github.dockerjava.api.command.StatsCmd
import com.github.dockerjava.api.exception.ConflictException as DockerConflictException
import com.github.dockerjava.api.exception.DockerClientException
import com.github.dockerjava.api.exception.DockerException
import com.github.dockerjava.api.exception.InternalServerErrorException as DockerInternalServerErrorException
import com.github.dockerjava.api.model.PullResponseItem
import com.github.dockerjava.api.model.ResponseItem
import com.github.dockerjava.api.model.Statistics
import io.dockistry.platform.docker.domain.DockerAgentUnavailableException
import io.dockistry.platform.docker.domain.DockerImagePullAccessDeniedException
import io.dockistry.platform.docker.domain.DockerNetworkRemoveConflictException
import io.dockistry.platform.docker.domain.DockerOperationFailedException
import io.dockistry.platform.docker.domain.DockerVolumeRemoveConflictException
import io.dockistry.platform.docker.domain.model.DockerImageRef
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalDockerAgentClientExceptionMappingTests {
    private lateinit var dockerClient: DockerClient
    private lateinit var agentClient: LocalDockerAgentClient

    @BeforeEach
    fun setUp() {
        dockerClient = Mockito.mock(DockerClient::class.java)
        agentClient = LocalDockerAgentClient(
            dockerClient = dockerClient,
            dockerAgentProperties = DockerAgentProperties(imagePullTimeout = 1_000)
        )
    }

    @Test
    fun `volume in use response is a remove conflict rather than an agent outage`() {
        stubRemoveVolumeFailure(
            DockerConflictException("volume is in use")
        )

        val exception = assertFailsWith<DockerVolumeRemoveConflictException> {
            agentClient.removeVolume(VOLUME_NAME)
        }

        assertEquals("volume is in use", exception.message)
        assertIs<DockerHttpResponseException>(exception.cause)
    }

    @Test
    fun `network in use response is a remove conflict rather than an agent outage`() {
        stubRemoveNetworkFailure(
            DockerException("network has active endpoints", HTTP_FORBIDDEN)
        )

        val exception = assertFailsWith<DockerNetworkRemoveConflictException> {
            agentClient.removeNetwork(NETWORK_ID)
        }

        assertEquals("network has active endpoints", exception.message)
        assertIs<DockerHttpResponseException>(exception.cause)
    }

    @Test
    fun `unmapped docker http response becomes a stable operation failure`() {
        stubRemoveVolumeFailure(
            DockerInternalServerErrorException("unexpected daemon failure")
        )

        val exception = assertFailsWith<DockerOperationFailedException> {
            agentClient.removeVolume(VOLUME_NAME)
        }

        assertIs<DockerHttpResponseException>(exception.cause)
    }

    @Test
    fun `docker client io failure is an agent outage`() {
        stubRemoveVolumeFailure(
            DockerClientException(
                "transport failed",
                IOException("socket closed")
            )
        )

        val exception = assertFailsWith<DockerAgentUnavailableException> {
            agentClient.removeVolume(VOLUME_NAME)
        }

        assertIs<DockerClientException>(exception.cause)
    }

    @Test
    fun `non transport docker client failure is a stable operation failure`() {
        stubRemoveVolumeFailure(
            DockerClientException("client processing failed")
        )

        val exception = assertFailsWith<DockerOperationFailedException> {
            agentClient.removeVolume(VOLUME_NAME)
        }

        assertIs<DockerClientException>(exception.cause)
    }

    @Test
    fun `empty Docker volume response maps to empty lookup results`() {
        val command = Mockito.mock(ListVolumesCmd::class.java)
        val response = Mockito.mock(ListVolumesResponse::class.java)

        Mockito.`when`(dockerClient.listVolumesCmd()).thenReturn(command)
        Mockito.`when`(command.withFilter("name", listOf(VOLUME_NAME)))
            .thenReturn(command)
        Mockito.`when`(command.exec()).thenReturn(response)
        Mockito.`when`(response.volumes).thenReturn(null)

        assertNull(agentClient.findVolumeByName(VOLUME_NAME))
        assertTrue(agentClient.listVolumes(DockerLabelsFilter()).isEmpty())
    }

    @Test
    fun `container deletion removes anonymous volumes`() {
        val command = Mockito.mock(RemoveContainerCmd::class.java)

        Mockito.`when`(dockerClient.removeContainerCmd(CONTAINER_ID)).thenReturn(command)
        Mockito.`when`(command.withForce(true)).thenReturn(command)
        Mockito.`when`(command.withRemoveVolumes(true)).thenReturn(command)

        agentClient.deleteContainer(CONTAINER_ID)

        Mockito.verify(command).withForce(true)
        Mockito.verify(command).withRemoveVolumes(true)
        Mockito.verify(command).exec()
    }

    @Test
    fun `pull stream error maps from captured registry status and keeps its message`() {
        val errorDetail = Mockito.mock(ResponseItem.ErrorDetail::class.java)
        Mockito.`when`(errorDetail.code).thenReturn(HTTP_UNAUTHORIZED)
        Mockito.`when`(errorDetail.message).thenReturn("registry credentials were rejected")

        val response = Mockito.mock(PullResponseItem::class.java)
        Mockito.`when`(response.isErrorIndicated).thenReturn(true)
        Mockito.`when`(response.errorDetail).thenReturn(errorDetail)
        Mockito.`when`(response.status).thenReturn("error")

        val command = Mockito.mock(PullImageCmd::class.java)
        Mockito.`when`(dockerClient.pullImageCmd(IMAGE_REPOSITORY)).thenReturn(command)
        Mockito.`when`(command.withTag(IMAGE_TAG)).thenReturn(command)
        Mockito.doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val callback = invocation.arguments.single()
                as ResultCallback<PullResponseItem>
            callback.onNext(response)
            callback.onComplete()
            callback
        }
            .`when`(command)
            .exec(Mockito.any())

        val exception = assertFailsWith<DockerImagePullAccessDeniedException> {
            agentClient.pullImage(
                DockerImageRef(
                    repository = IMAGE_REPOSITORY,
                    tag = IMAGE_TAG
                )
            )
        }

        assertEquals("registry credentials were rejected", exception.message)
        assertIs<DockerClientException>(exception.cause)
    }

    @Test
    fun `interrupted stats wait restores interrupt flag and propagates interruption`() {
        val command = Mockito.mock(StatsCmd::class.java)

        Mockito.`when`(dockerClient.statsCmd(CONTAINER_ID)).thenReturn(command)
        Mockito.`when`(command.withNoStream(true)).thenReturn(command)
        Mockito.doAnswer { invocation -> invocation.arguments.single() }
            .`when`(command)
            .exec(Mockito.any())

        try {
            Thread.currentThread().interrupt()
            assertFailsWith<InterruptedException> {
                agentClient.getContainerStats(CONTAINER_ID)
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    private fun stubRemoveVolumeFailure(failure: RuntimeException) {
        val command = Mockito.mock(RemoveVolumeCmd::class.java)
        Mockito.`when`(dockerClient.removeVolumeCmd(VOLUME_NAME)).thenReturn(command)
        Mockito.doThrow(failure).`when`(command).exec()
    }

    private fun stubRemoveNetworkFailure(failure: RuntimeException) {
        val command = Mockito.mock(RemoveNetworkCmd::class.java)
        Mockito.`when`(dockerClient.removeNetworkCmd(NETWORK_ID)).thenReturn(command)
        Mockito.doThrow(failure).`when`(command).exec()
    }

    private companion object {
        const val VOLUME_NAME = "test-volume"
        const val NETWORK_ID = "test-network"
        const val CONTAINER_ID = "test-container"
        const val IMAGE_REPOSITORY = "registry.example/test"
        const val IMAGE_TAG = "latest"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
