package io.dockistry.platform.email.application.ports

import io.dockistry.platform.email.domain.EmailSendJobContext

interface EmailJobHandler {
    fun getContextTypeKey(): String
    fun getContextType(): Class<out EmailSendJobContext>
    fun handle(context: EmailSendJobContext)
}
