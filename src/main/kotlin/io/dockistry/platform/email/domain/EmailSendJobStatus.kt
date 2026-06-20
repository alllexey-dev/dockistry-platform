package io.dockistry.platform.email.domain

enum class EmailSendJobStatus {
    PENDING,
    PROCESSING,
    FAILED,
    SENT
}