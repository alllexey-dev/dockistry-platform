package io.dockistry.platform.projects.domain.dto

import io.dockistry.platform.iam.domain.dto.PrincipalInfoDto
import io.dockistry.platform.projects.domain.ProjectStatus
import java.time.Instant
import java.util.UUID

data class ProjectInfoDto(
    val id: UUID,
    val name: String,
    val slug: String,
    val description: String?,
    val ownerPrincipalInfo: PrincipalInfoDto,
    val status: ProjectStatus,
    val createdAt: Instant,
    val version: Long
)
