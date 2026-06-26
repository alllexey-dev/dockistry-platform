package io.dockistry.platform.projects.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.infrastructure.PrincipalInfoQueryRepository
import io.dockistry.platform.iam.infrastructure.PrincipalRepository
import io.dockistry.platform.iam.infrastructure.RoleBindingRepository
import io.dockistry.platform.iam.infrastructure.RoleRepository
import io.dockistry.platform.projects.domain.ProjectMemberNotFoundException
import io.dockistry.platform.projects.domain.ProjectMemberPrincipalNotFoundException
import io.dockistry.platform.projects.domain.ProjectRoleAlreadyAssignedException
import io.dockistry.platform.projects.domain.ProjectRoleBindingNotFoundException
import io.dockistry.platform.projects.domain.ProjectRoleNotAssignableException
import io.dockistry.platform.projects.domain.dto.ProjectMemberDto
import io.dockistry.platform.projects.domain.dto.RoleBindingDto
import io.dockistry.platform.projects.domain.dto.RoleDto
import io.dockistry.platform.projects.infrastructure.ProjectMemberRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
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
    private val roleBindingRepository: RoleBindingRepository
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
        subjectPrincipal.roleBindings.add(binding)
        project.scope.roleBindings.add(binding)
        role.roleBindings.add(binding)

        val savedBinding = roleBindingRepository.saveAndFlush(binding)

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
        projectAccessGuard.requireMemberManageable(projectId, managerPrincipal)

        if (!roleBindingRepository.isAssigned(projectId, roleBindingId, subjectPrincipalId)) {
            throw ProjectRoleBindingNotFoundException(
                "Role binding $roleBindingId couldn't be found " +
                    "for principal $subjectPrincipalId in project $projectId"
            )
        }

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
        projectAccessGuard.requireMemberManageable(projectId, managerPrincipal)

        if (!projectMemberRepository.isMember(projectId, subjectPrincipalId)) {
            throw ProjectMemberNotFoundException(
                "Project member $subjectPrincipalId couldn't be found in project $projectId"
            )
        }

        roleBindingRepository.deleteAllByProjectAndPrincipal(
            projectId = projectId,
            principalId = subjectPrincipalId
        )
    }

    private fun missingPrincipalInfo(principalId: UUID): InternalDataIntegrityException {
        return InternalDataIntegrityException(
            "Principal $principalId has project role bindings, but couldn't be resolved"
        )
    }
}
