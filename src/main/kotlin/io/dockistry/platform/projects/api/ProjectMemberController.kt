package io.dockistry.platform.projects.api

import io.dockistry.platform.iam.application.CurrentUserProvider
import io.dockistry.platform.projects.application.ProjectMemberService
import io.dockistry.platform.projects.domain.dto.AssignRoleRequest
import io.dockistry.platform.projects.domain.dto.AssignRoleResponse
import io.dockistry.platform.projects.domain.dto.ProjectMemberDto
import io.dockistry.platform.projects.domain.dto.RoleDto
import io.dockistry.platform.shared.api.ApiResponse
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/projects/{projectId}")
class ProjectMemberController(
    private val currentUserProvider: CurrentUserProvider,
    private val projectMemberService: ProjectMemberService
) {
    @GetMapping("/members")
    fun listMembers(
        @PathVariable projectId: UUID
    ): ApiResponse<List<ProjectMemberDto>> {
        val user = currentUserProvider.getUser()
        return ApiResponse(projectMemberService.list(user.principal, projectId))
    }

    @GetMapping("/assignable-roles")
    fun listAssignableRoles(
        @PathVariable projectId: UUID
    ): ApiResponse<List<RoleDto>> {
        val user = currentUserProvider.getUser()
        return ApiResponse(projectMemberService.listAvailableRoles(user.principal, projectId))
    }

    @PostMapping("/members/{principalId}/roles")
    fun assignRole(
        @PathVariable projectId: UUID,
        @PathVariable principalId: UUID,
        @RequestBody request: AssignRoleRequest
    ): ApiResponse<AssignRoleResponse> {
        val user = currentUserProvider.getUser()
        val binding = projectMemberService.assignRole(
            managerPrincipal = user.principal,
            projectId = projectId,
            subjectPrincipalId = principalId,
            roleId = request.roleId
        )
        return ApiResponse(AssignRoleResponse(binding.roleBindingId))
    }

    @DeleteMapping("/members/{principalId}/roles/{roleBindingId}")
    fun deleteRoleBinding(
        @PathVariable projectId: UUID,
        @PathVariable principalId: UUID,
        @PathVariable roleBindingId: UUID
    ): ApiResponse<String> {
        val user = currentUserProvider.getUser()
        projectMemberService.deleteRoleBinding(
            managerPrincipal = user.principal,
            projectId = projectId,
            subjectPrincipalId = principalId,
            roleBindingId = roleBindingId
        )
        return ApiResponse("ok")
    }

    @DeleteMapping("/members/{principalId}")
    fun deleteMember(
        @PathVariable projectId: UUID,
        @PathVariable principalId: UUID
    ): ApiResponse<String> {
        val user = currentUserProvider.getUser()
        projectMemberService.deleteMember(
            managerPrincipal = user.principal,
            projectId = projectId,
            subjectPrincipalId = principalId
        )
        return ApiResponse("ok")
    }
}
