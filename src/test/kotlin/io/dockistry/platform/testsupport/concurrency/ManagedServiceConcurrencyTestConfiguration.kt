package io.dockistry.platform.testsupport.concurrency

import com.github.dockerjava.api.DockerClient
import io.dockistry.platform.docker.application.ports.DockerAgentClient
import io.dockistry.platform.docker.domain.model.DockerContainerSummary
import io.dockistry.platform.docker.domain.model.DockerLabelsFilter
import io.dockistry.platform.docker.infrastructure.DockerAgentProperties
import io.dockistry.platform.docker.infrastructure.LocalDockerAgentClient
import jakarta.persistence.EntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.time.Clock

@TestConfiguration(proxyBeanMethods = false)
@Import(ClockConcurrencyTestConfiguration::class)
class ManagedServiceConcurrencyTestConfiguration {
    @Bean
    fun managedServiceConcurrencyFixtures(
        entityManager: EntityManager,
        concurrency: ConcurrencyTestSupport,
        clock: Clock
    ) = ManagedServiceConcurrencyFixtures(entityManager, concurrency, clock)

    @Bean
    @Primary
    fun emptyContainerDockerAgentClient(
        dockerClient: DockerClient,
        properties: DockerAgentProperties
    ): DockerAgentClient = object : LocalDockerAgentClient(dockerClient, properties) {
        override fun listContainers(filter: DockerLabelsFilter): List<DockerContainerSummary> =
            emptyList()
    }
}
