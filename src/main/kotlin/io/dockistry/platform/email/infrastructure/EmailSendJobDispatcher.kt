package io.dockistry.platform.email.infrastructure

import io.dockistry.platform.email.application.ports.EmailJobHandler
import io.dockistry.platform.email.domain.EmailSendJobContext
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class EmailSendJobDispatcher(
    jobHandlers: List<EmailJobHandler>,
    private val objectMapper: ObjectMapper
) {
    private val handlersMap: Map<String, EmailJobHandler> =
        jobHandlers.associateBy { it.getContextTypeKey() }
            .also {
                require(it.size == jobHandlers.size) {
                    "Duplicate email job handlers registered for the same context type key"
                }
            }

    fun dispatch(contextTypeKey: String, jobContext: String) {
        val handler = handlersMap[contextTypeKey]
            ?: throw InternalDataIntegrityException("No email job handler registered for context type key $contextTypeKey")

        handler.handle(readContext(contextTypeKey, jobContext, handler))
    }

    private fun readContext(
        contextTypeKey: String,
        jobContext: String,
        handler: EmailJobHandler
    ): EmailSendJobContext {
        return try {
            objectMapper.readValue(jobContext, handler.getContextType())
        } catch (exception: RuntimeException) {
            throw InternalDataIntegrityException(
                "Email job context for context type key $contextTypeKey could not be deserialized " +
                    "to ${handler.getContextType().simpleName}: ${exception.message}"
            )
        }
    }
}
