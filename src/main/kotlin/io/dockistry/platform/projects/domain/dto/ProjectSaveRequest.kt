package io.dockistry.platform.projects.domain.dto

data class ProjectSaveRequest(
    val name: String,
    val slug: String,
    val description: String?
)
