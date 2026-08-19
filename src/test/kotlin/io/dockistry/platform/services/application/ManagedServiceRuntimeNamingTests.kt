package io.dockistry.platform.services.application

import io.dockistry.platform.docker.application.ContainerService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import kotlin.test.assertEquals

class ManagedServiceRuntimeNamingTests {
    private val containerService = mock(ContainerService::class.java)
    private val projectVolumeService = ProjectVolumeService(containerService)

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
