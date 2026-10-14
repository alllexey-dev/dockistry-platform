package io.dockistry.platform.projects.application

import io.dockistry.platform.iam.application.PermissionCheckService
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.iam.infrastructure.GroupRepository
import io.dockistry.platform.iam.infrastructure.PrincipalInfoQueryRepository
import io.dockistry.platform.iam.infrastructure.PrincipalRepository
import io.dockistry.platform.iam.infrastructure.RoleBindingRepository
import io.dockistry.platform.iam.infrastructure.RoleRepository
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.ProjectMemberNotFoundException
import io.dockistry.platform.projects.domain.ProjectMemberPrincipalNotFoundException
import io.dockistry.platform.projects.domain.ProjectRoleAlreadyAssignedException
import io.dockistry.platform.projects.domain.ProjectRoleBindingNotFoundException
import io.dockistry.platform.projects.domain.ProjectRoleNotAssignableException
import io.dockistry.platform.projects.domain.dto.ProjectMemberDto
import io.dockistry.platform.projects.domain.dto.RoleBindingDto
import io.dockistry.platform.projects.domain.dto.RoleDto
import io.dockistry.platform.projects.infrastructure.ProjectMemberRepository
import io.dockistry.platform.secrets.application.SecretDelegationPermissionGuard
import io.dockistry.platform.secrets.domain.SecretDelegationActiveException
import io.dockistry.platform.secrets.infrastructure.ProjectSecretDelegationRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProjectMemberService(
    private val projectAccessGuard: ProjectAccessGuard,
    private val projectMemberRepository: ProjectMemberRepository,
    private val principalInfoQueryRepository: PrincipalInfoQueryRepository,
    private val principalRepository: PrincipalRepository,
    private val roleRepository: RoleRepository,
    private val roleBindingRepository: RoleBindingRepository,
    private val secretDelegationPermissionGuard: SecretDelegationPermissionGuard,
    private val permissionCheckService: PermissionCheckService,
    private val groupRepository: GroupRepository,
    private val projectSecretDelegationRepository: ProjectSecretDelegationRepository
) {
    @Transactional
    fun list(principal: Principal, projectId: UUID): List<ProjectMemberDto> {
        projectAccessGuard.requireReadable(projectId, principal)

        val bindingsByPrincipalId = projectMemberRepository.listByProject(projectId)
        val principalInfoById = principalInfoQueryRepository.getInfoByIds(
            bindingsByPrincipalId.keys.toList()
        )

        return bindingsByPrincipalId.map { (principalId, bindings) ->
            ProjectMemberDto(
                principalInfo = principalInfoById[principalId]
                    ?: throw missingPrincipalInfo(principalId),
                roles = bindings
            )
        }
    }

    @Transactional
    fun listAvailableRoles(principal: Principal, projectId: UUID): List<RoleDto> {
        projectAccessGuard.requireReadable(projectId, principal)
        return projectMemberRepository.listRolesByProject(projectId)
    }

    @Transactional
    fun assignRole(
        managerPrincipal: Principal,
        projectId: UUID,
        subjectPrincipalId: UUID,
        roleId: UUID
    ): RoleBindingDto {
        val project = projectAccessGuard.requireMemberManageable(projectId, managerPrincipal)

        if (!projectMemberRepository.isAvailableInProject(projectId, roleId)) {
            throw ProjectRoleNotAssignableException(
                "Role $roleId is not assignable in project $projectId"
            )
        }

        if (roleBindingRepository.isRoleAssigned(projectId, roleId, subjectPrincipalId)) {
            throw ProjectRoleAlreadyAssignedException(
                "Role $roleId is already assigned to principal $subjectPrincipalId in project $projectId"
            )
        }

        val subjectPrincipal = principalRepository.findPrincipalById(subjectPrincipalId)
            ?: throw ProjectMemberPrincipalNotFoundException(
                "Principal $subjectPrincipalId couldn't be found"
            )
        val role = roleRepository.findById(roleId).orElseThrow {
            InternalDataIntegrityException(
                "Role $roleId was just checked as assignable, but couldn't be found"
            )
        }

        val binding = RoleBinding(
            subjectPrincipal = subjectPrincipal,
            scope = project.scope,
            role = role
        )

        val savedBinding = try {
            roleBindingRepository.saveAndFlush(binding)
        } catch (exception: DataIntegrityViolationException) {
            throw handleRoleBindingIntegrityViolation(
                projectId = projectId,
                roleId = roleId,
                subjectPrincipalId = subjectPrincipalId,
                exception = exception
            )
        }

        return RoleBindingDto(
            roleBindingId = savedBinding.id,
            roleId = role.id
        )
    }

    @Transactional
    fun deleteRoleBinding(
        managerPrincipal: Principal,
        projectId: UUID,
        subjectPrincipalId: UUID,
        roleBindingId: UUID
    ) {
        val project = projectAccessGuard.requireMemberManageable(projectId, managerPrincipal)
        val groupMemberIds = lockSubjectAndGroupMembers(subjectPrincipalId)

        if (!roleBindingRepository.isAssigned(projectId, roleBindingId, subjectPrincipalId)) {
            throw ProjectRoleBindingNotFoundException(
                "Role binding $roleBindingId couldn't be found " +
                    "for principal $subjectPrincipalId in project $projectId"
            )
        }

        requirePermissionRevocationAllowed(
            subjectPrincipalId,
            project,
            listOf(roleBindingId),
            groupMemberIds
        )

        roleBindingRepository.deleteBindingInProject(
            projectId = projectId,
            principalId = subjectPrincipalId,
            roleBindingId = roleBindingId
        )
    }

    @Transactional
    fun deleteMember(
        managerPrincipal: Principal,
        projectId: UUID,
        subjectPrincipalId: UUID
    ) {
        val project = projectAccessGuard.requireMemberManageable(projectId, managerPrincipal)
        val groupMemberIds = lockSubjectAndGroupMembers(subjectPrincipalId)

        if (!projectMemberRepository.isMember(projectId, subjectPrincipalId)) {
            throw ProjectMemberNotFoundException(
                "Project member $subjectPrincipalId couldn't be found in project $projectId"
            )
        }

        val removedBindingIds = roleBindingRepository.findByScopeAndSubjectPrincipalId(
            scope = project.scope,
            subjectPrincipalId = subjectPrincipalId
        ).map { it.id }

        requirePermissionRevocationAllowed(
            subjectPrincipalId = subjectPrincipalId,
            project = project,
            removedBindingIds = removedBindingIds,
            groupMemberIds = groupMemberIds
        )

        /* Concurrent grants must not be deleted after serving as a remaining permission
        source; old version of this method was deleting all bindings at once. */
        roleBindingRepository.deleteBindingsInProject(
            projectId = projectId,
            principalId = subjectPrincipalId,
            roleBindingIds = removedBindingIds
        )
    }

    private fun requirePermissionRevocationAllowed(
        subjectPrincipalId: UUID,
        project: Project,
        removedBindingIds: List<UUID>,
        groupMemberIds: List<UUID>
    ) {
        // For USER groupMemberIds is empty and the check covers only the subject itself.
        val delegatedOwnerIds = projectSecretDelegationRepository
            .findDelegatedOwnerIdsByProjectAndOwnerPrincipalIds(
                projectId = project.id,
                ownerPrincipalIds = groupMemberIds + subjectPrincipalId
            )

        if (delegatedOwnerIds.isEmpty()) return

        val conflictingOwnerIds = permissionCheckService
            .findPrincipalIdsWithoutPermissionExcludingBindings(
                key = SystemPermissionNames.SECRETS_DELEGATE.key,
                principalIds = delegatedOwnerIds,
                scopeId = project.scope.id,
                excludedBindingIds = removedBindingIds
            )

        if (conflictingOwnerIds.isNotEmpty()) {
            throw activeDelegationException(project.id)
        }
    }

    private fun activeDelegationException(projectId: UUID) = SecretDelegationActiveException(
        "Cannot remove the last effective project.secrets.delegate permission " +
            "while owned secrets are delegated to project $projectId. " +
            "Force-revoke the blocking delegations first."
    )

    private fun lockSubjectAndGroupMembers(principalId: UUID): List<UUID> {
        secretDelegationPermissionGuard.lockPrincipal(principalId)

        val subject = principalRepository.findPrincipalById(principalId)
        if (subject?.principalType == PrincipalType.GROUP) {
            // Include users without delegations to serialize a concurrent first delegation.
            val memberIds = groupRepository.findMemberIdsByPrincipalId(principalId).sorted()
            memberIds.forEach(secretDelegationPermissionGuard::lockPrincipal)
            return memberIds
        }

        return emptyList()
    }

    private fun missingPrincipalInfo(principalId: UUID): InternalDataIntegrityException {
        return InternalDataIntegrityException(
            "Principal $principalId has project role bindings, but couldn't be resolved"
        )
    }

    private fun handleRoleBindingIntegrityViolation(
        projectId: UUID,
        roleId: UUID,
        subjectPrincipalId: UUID,
        exception: DataIntegrityViolationException
    ): RuntimeException {
        if (roleBindingRepository.isRoleAssigned(projectId, roleId, subjectPrincipalId)) {
            return ProjectRoleAlreadyAssignedException(
                "Role $roleId is already assigned to principal $subjectPrincipalId in project $projectId"
            )
        }

        return exception
    }
}
