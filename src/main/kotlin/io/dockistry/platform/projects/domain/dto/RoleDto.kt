package io.dockistry.platform.projects.domain.dto

import java.util.UUID

data class RoleDto(
    val id: UUID,
    val roleName: String,
    val permissions: List<PermissionDto>
)
