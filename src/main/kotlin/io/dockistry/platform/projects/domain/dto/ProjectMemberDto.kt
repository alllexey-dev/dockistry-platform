package io.dockistry.platform.projects.domain.dto

import io.dockistry.platform.iam.domain.dto.PrincipalInfoDto

data class ProjectMemberDto(
    val principalInfo: PrincipalInfoDto,
    val roles: List<RoleBindingDto>
)
