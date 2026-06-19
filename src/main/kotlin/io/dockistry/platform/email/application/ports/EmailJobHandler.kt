package io.dockistry.platform.email.application.ports

import io.dockistry.platform.email.domain.EmailSendJobContext

interface EmailJobHandler<C: EmailSendJobContext> {
    fun handle(context: C)
}
