package io.dockistry.platform.docker.testsupport

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.time.Duration

class DockerInDockerContainer : GenericContainer<DockerInDockerContainer>(IMAGE) {
    init {
        withPrivilegedMode(true)
        withEnv("DOCKER_TLS_CERTDIR", "")
        withExposedPorts(DOCKER_PORT)
        withCommand(
            "dockerd-entrypoint.sh",
            "--host=tcp://0.0.0.0:$DOCKER_PORT",
            "--host=unix:///var/run/docker.sock"
        )
        waitingFor(
            Wait.forHttp("/_ping")
                .forPort(DOCKER_PORT)
                .forResponsePredicate { it.trim() == "OK" }
                .withStartupTimeout(Duration.ofSeconds(120))
        )
    }

    fun dockerHostUri(): URI {
        return URI.create("tcp://$host:${getMappedPort(DOCKER_PORT)}")
    }

    companion object {
        private const val DOCKER_PORT = 2375
        private val IMAGE = DockerImageName.parse("docker:27-dind")
    }
}
