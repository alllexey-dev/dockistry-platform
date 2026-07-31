package io.dockistry.platform.docker.infrastructure

import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.model.PullResponseItem

internal class DockerImagePullResultCallback : PullImageResultCallback() {
    var failure: DockerImagePullFailure? = null
        private set

    override fun onNext(item: PullResponseItem) {
        if (item.isErrorIndicated) {
            failure = DockerImagePullFailure(
                statusCode = item.errorDetail?.code,
                message = item.errorDetail?.message
                    ?.takeIf { it.isNotBlank() }
                    ?: item.status?.takeIf { it.isNotBlank() }
                    ?: "Docker image pull failed"
            )
        }

        super.onNext(item)
    }
}

internal data class DockerImagePullFailure(
    val statusCode: Int?,
    val message: String
)
