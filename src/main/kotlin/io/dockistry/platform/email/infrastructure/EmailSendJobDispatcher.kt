package io.dockistry.platform.email.infrastructure

import io.dockistry.platform.email.application.ports.EmailJobHandler
import io.dockistry.platform.email.domain.EmailSendJobContext
import io.dockistry.platform.email.domain.EmailSendJobType
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service

@Service
class EmailSendJobDispatcher(
    jobHandlers: List<EmailJobHandler>
) {
    private val handlersMap: Map<EmailSendJobType, EmailJobHandler> =
        jobHandlers.associateBy { it.getJobType() }
            .also {
                require(it.size == jobHandlers.size) {
                    "Duplicate email job handlers registered for the same job type"
                }
            }

    fun dispatch(jobType: EmailSendJobType, context: EmailSendJobContext) {
        val handler = handlersMap[jobType]
            ?: throw InternalDataIntegrityException("No email job handler registered for job type $jobType")

        handler.handle(context)
    }
}
