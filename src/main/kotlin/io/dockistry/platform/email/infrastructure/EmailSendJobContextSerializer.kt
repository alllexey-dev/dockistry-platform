package io.dockistry.platform.email.infrastructure

import io.dockistry.platform.email.domain.EmailSendJob
import io.dockistry.platform.email.domain.EmailSendJobContext
import io.dockistry.platform.email.domain.EmailSendJobType
import io.dockistry.platform.email.domain.VerificationEmailJobContext
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class EmailSendJobContextSerializer(
    private val objectMapper: ObjectMapper
) {
    fun serialize(context: EmailSendJobContext): String {
        return objectMapper.writeValueAsString(context)
    }

    fun deserialize(
        context: String, type: EmailSendJobType
    ): EmailSendJobContext {
        return when (type) {
            EmailSendJobType.EMAIL_VERIFICATION -> objectMapper.readValue(
                context, VerificationEmailJobContext::class.java
            )
        }
    }

    fun deserialize(job: EmailSendJob): EmailSendJobContext =
        deserialize(job.jobContext, job.jobType)
}