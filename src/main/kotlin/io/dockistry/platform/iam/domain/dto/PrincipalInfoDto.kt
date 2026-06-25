package io.dockistry.platform.iam.domain.dto

import io.dockistry.platform.iam.domain.PrincipalType
import java.util.UUID

data class PrincipalInfoDto(
    val id: UUID,
    val type: PrincipalType,
    val name: String
)
