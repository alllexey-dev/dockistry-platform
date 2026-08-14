package io.dockistry.platform.docker.infrastructure

import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.model.PullResponseItem
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal class DockerImagePullResultCallback : PullImageResultCallback() {
    private val closeRequested = AtomicBoolean(false)

    var failure: DockerImagePullFailure? = null
        private set

    override fun onStart(stream: Closeable) {
        if (closeRequested.get()) {
            stream.close()
            return
        }

        super.onStart(stream)

        if (closeRequested.get()) {
            super.close()
        }
    }

    override fun close() {
        closeRequested.set(true)
        super.close()
    }

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
