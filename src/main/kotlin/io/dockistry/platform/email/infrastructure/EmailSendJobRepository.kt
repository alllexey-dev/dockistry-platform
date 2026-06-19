package io.dockistry.platform.email.infrastructure

import io.dockistry.platform.email.domain.EmailSendJob
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface EmailSendJobRepository : JpaRepository<EmailSendJob, UUID> {
}