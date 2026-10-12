package io.dockistry.platform.secrets.domain.dto

import io.dockistry.platform.iam.domain.dto.PrincipalInfoDto
import java.time.Instant

/** Secret delegated to a project, as seen through `project.read`: metadata only, no values. */
data class ProjectSecretDto(
    val id: String,
    val name: String,
    val description: String?,
    val owner: PrincipalInfoDto,
    val delegatedAt: Instant,
    val versions: List<SecretVersionDto>,
    val usage: List<SecretUsageDto>
)
