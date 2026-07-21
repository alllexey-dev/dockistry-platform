package io.dockistry.platform.services.application

import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.projects.domain.Project
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class ManagedServiceRuntimeNamingTests {
    private val dockerAgentClient = mock(DockerAgentClient::class.java)
    private val projectNetworkService = ProjectNetworkService(dockerAgentClient)
    private val projectVolumeService = ProjectVolumeService(dockerAgentClient)

    @Test
    fun `runtime resource names stay unchanged after project slug update`() {
        val project = Project(
            name = "Runtime Naming",
            slug = "runtime-naming",
            ownerPrincipal = Principal(PrincipalType.USER),
            scope = Scope(ScopeType.PROJECT),
            createdAt = Instant.parse("2026-01-01T00:00:00Z")
        ).apply {
            id = UUID.randomUUID()
        }
        val serviceName = "backend"
        val volumeName = "data"
        val expectedNames = listOf(
            "dockistry_${project.id}",
            "dockistry_${project.id}_${serviceName}",
            "dockistry_${project.id}_${serviceName}_$volumeName"
        )

        assertEquals(
            expectedNames,
            runtimeResourceNames(project, serviceName, volumeName)
        )

        project.slug = "renamed-project"

        assertEquals(
            expectedNames,
            runtimeResourceNames(project, serviceName, volumeName)
        )
    }

    private fun runtimeResourceNames(
        project: Project,
        serviceName: String,
        volumeName: String
    ): List<String> {
        return listOf(
            projectNetworkService.projectNetworkName(project.id),
            ManagedServiceDeployExecutor.containerName(project.id, serviceName),
            projectVolumeService.fullVolumeName(project.id, serviceName, volumeName)
        )
    }
}
