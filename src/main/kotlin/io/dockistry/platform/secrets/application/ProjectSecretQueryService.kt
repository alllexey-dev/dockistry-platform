package io.dockistry.platform.secrets.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.infrastructure.PrincipalInfoQueryRepository
import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import io.dockistry.platform.secrets.domain.SecretNotFoundException
import io.dockistry.platform.secrets.domain.dto.ProjectSecretDto
import io.dockistry.platform.secrets.infrastructure.ManagedServiceSecretReferenceRepository
import io.dockistry.platform.secrets.infrastructure.ProjectSecretDelegationRepository
import io.dockistry.platform.secrets.infrastructure.SecretVersionRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Read side of Secrets delegated to a project, authorized by `project.read`. */
@Service
class ProjectSecretQueryService(
    private val projectAccessGuard: ProjectAccessGuard,
    private val delegationRepository: ProjectSecretDelegationRepository,
    private val secretVersionRepository: SecretVersionRepository,
    private val referenceRepository: ManagedServiceSecretReferenceRepository,
    private val principalInfoQueryRepository: PrincipalInfoQueryRepository
) {
    @Transactional(readOnly = true)
    fun list(projectId: UUID, principal: Principal): List<ProjectSecretDto> {
        projectAccessGuard.requireReadable(projectId, principal)
        return toDtos(projectId, delegationRepository.findByProjectIdWithSecret(projectId))
    }

    /** A Secret that isn't delegated to the project is reported as missing. */
    @Transactional(readOnly = true)
    fun get(projectId: UUID, secretId: String, principal: Principal): ProjectSecretDto {
        projectAccessGuard.requireReadable(projectId, principal)
        val delegation = delegationRepository.getByProjectAndSecret(projectId, secretId)
            ?: throw SecretNotFoundException("Secret with provided ID couldn't be found")

        return toDtos(projectId, listOf(delegation)).single()
    }

    private fun toDtos(
        projectId: UUID,
        delegations: List<ProjectSecretDelegation>
    ): List<ProjectSecretDto> {
        if (delegations.isEmpty()) return emptyList()

        val secretIds = delegations.map { it.secret.id }
        val versions = secretVersionRepository.findBySecretIdIn(secretIds)
            .groupBy { it.secret.id }
        val usage = referenceRepository.findUsageByProjectAndSecretIds(projectId, secretIds)
            .groupBy { it.secret.id }
        val owners = principalInfoQueryRepository.getInfoByIds(
            delegations.map { it.secret.ownerPrincipal.id }.distinct()
        )

        return delegations.map { delegation ->
            val secret = delegation.secret
            ProjectSecretDto(
                id = secret.id,
                name = secret.name,
                description = secret.description,
                owner = owners[secret.ownerPrincipal.id]
                    ?: throw InternalDataIntegrityException(
                        "Owner principal of Secret ${secret.id} doesn't exist"
                    ),
                delegatedAt = delegation.createdAt,
                versions = versions[secret.id].orEmpty().toVersionDtos(),
                usage = usage[secret.id].orEmpty().map { it.toUsageDto() }
            )
        }
    }
}
