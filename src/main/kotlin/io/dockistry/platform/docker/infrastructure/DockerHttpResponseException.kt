package io.dockistry.platform.docker.infrastructure

import com.github.dockerjava.api.exception.DockerException

internal class DockerHttpResponseException(
    val statusCode: Int,
    val responseMessage: String,
    cause: DockerException
) : RuntimeException(responseMessage, cause)
