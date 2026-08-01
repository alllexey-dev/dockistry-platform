package io.dockistry.platform.projects.domain.dto

import jakarta.validation.constraints.PositiveOrZero

data class ProjectUpdateRequest(
    val name: String,
    val description: String?,
    @field:PositiveOrZero(message = "Project expectedVersion must not be negative")
    val expectedVersion: Long
)
