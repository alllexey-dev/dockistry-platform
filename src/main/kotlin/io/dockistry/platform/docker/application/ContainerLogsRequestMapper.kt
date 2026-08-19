package io.dockistry.platform.docker.application

import io.dockistry.platform.docker.domain.InvalidContainerLogsRequestException
import io.dockistry.platform.docker.domain.model.DockerContainerLogStream
import io.dockistry.platform.docker.domain.model.DockerContainerLogsRequest
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ContainerLogsRequestMapper {
    fun fromQueryParameters(
        tail: Int?,
        since: Instant?,
        until: Instant?,
        timestamps: Boolean?,
        streams: String?
    ): DockerContainerLogsRequest {
        return DockerContainerLogsRequest(
            tail = tail ?: DockerContainerLogsRequest.DEFAULT_TAIL,
            since = since,
            until = until,
            timestamps = timestamps ?: false,
            streams = parseStreams(streams)
        )
    }

    private fun parseStreams(streams: String?): Set<DockerContainerLogStream> {
        if (streams.isNullOrBlank()) {
            return setOf(
                DockerContainerLogStream.STDOUT,
                DockerContainerLogStream.STDERR
            )
        }

        return streams.split(",")
            .map { it.trim().lowercase() }
            .map { stream ->
                when (stream) {
                    "stdout" -> DockerContainerLogStream.STDOUT
                    "stderr" -> DockerContainerLogStream.STDERR
                    else -> throw InvalidContainerLogsRequestException(
                        "Container logs streams must contain only stdout or stderr"
                    )
                }
            }
            .toSet()
    }
}
