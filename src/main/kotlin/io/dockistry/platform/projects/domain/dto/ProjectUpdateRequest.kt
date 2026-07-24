package io.dockistry.platform.projects.domain.dto

data class ProjectUpdateRequest(
    val name: String,
    val slug: String,
    val description: String?,
    val expectedVersion: Long
)
