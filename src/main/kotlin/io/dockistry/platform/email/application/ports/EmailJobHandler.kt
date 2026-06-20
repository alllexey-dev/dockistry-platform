package io.dockistry.platform.email.application.ports

import io.dockistry.platform.email.domain.EmailSendJobContext
import io.dockistry.platform.email.domain.EmailSendJobType

interface EmailJobHandler {
    fun getJobType(): EmailSendJobType
    fun handle(context: EmailSendJobContext)
}

abstract class TypedEmailJobHandler<C : EmailSendJobContext>(
    private val contextType: Class<C>
) : EmailJobHandler {

    protected fun requireContext(context: EmailSendJobContext): C {
        require(contextType.isInstance(context)) {
            "Email job handler for ${getJobType()} expected ${contextType.simpleName}, " +
                "but got ${context::class.simpleName}"
        }

        return contextType.cast(context)
    }
}
