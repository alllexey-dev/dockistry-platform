package io.dockistry.platform.projects.domain.dto

import jakarta.validation.constraints.NotNull
import java.util.UUID

data class AssignRoleRequest(
    @field:NotNull(message = "roleId must not be null")
    val roleId: UUID?
)
