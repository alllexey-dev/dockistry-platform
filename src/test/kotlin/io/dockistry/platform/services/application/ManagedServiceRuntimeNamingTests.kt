package io.dockistry.platform.services.application

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import kotlin.test.assertEquals

class ManagedServiceRuntimeNamingTests {
    private val dockerAgentClient = mock(DockerAgentClient::class.java)
    private val projectVolumeService = ProjectVolumeService(dockerAgentClient)

    @Test
    fun `managed volume name uses project slug and service name`() {
        assertEquals(
            "dockistry_runtime-naming_backend_data",
            projectVolumeService.fullVolumeName(
                projectSlug = "runtime-naming",
                serviceName = "backend",
                volumeName = "data"
            )
        )
    }
}
