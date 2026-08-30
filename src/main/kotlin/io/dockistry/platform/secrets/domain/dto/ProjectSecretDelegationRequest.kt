package io.dockistry.platform.secrets.domain.dto

import java.util.UUID

data class ProjectSecretDelegationRequest(
    val projectId: UUID,
)
