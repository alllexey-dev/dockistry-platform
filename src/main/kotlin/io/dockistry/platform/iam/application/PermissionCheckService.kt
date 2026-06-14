package io.dockistry.platform.iam.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.infrastructure.PrincipalRepository
import io.dockistry.platform.iam.infrastructure.UserRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PermissionCheckService(
    private val principalRepository: PrincipalRepository,
    private val userRepository: UserRepository,
) {
    @Transactional
    fun hasPermission(key: String, principalId: UUID, scopeId: UUID): Boolean {
        val principal = loadPrincipal(principalId)
        return hasPermission(key, principal, scopeId)
    }

    @Transactional
    fun hasGlobalPermission(key: String, principalId: UUID): Boolean {
        val principal = loadPrincipal(principalId)
        return hasPermission(key, principal)
    }

    private fun hasPermission(key: String, principal: Principal, scopeId: UUID? = null): Boolean {
        if (
            principal.roleBindings.any { binding ->
                (binding.scope.id == scopeId || binding.scope.scopeType == ScopeType.GLOBAL) &&
                        binding.role.permissions.any { permission ->
                            permission.key == key
                        }
            }
        ) {
            return true
        }

        return if (principal.principalType == PrincipalType.USER) {
            userHasPermissionInGroup(key, principal, scopeId)
        } else {
            false
        }
    }

    private fun userHasPermissionInGroup(key: String, userPrincipal: Principal, scopeId: UUID? = null): Boolean {
        val user = userRepository.findByPrincipal(userPrincipal)
            ?: throw InternalDataIntegrityException("Principal ${userPrincipal.id} was passed for user's groups permission check, but corresponding User entity couldn't be found")

        return user.groups.any { group ->
            hasPermission(key, group.principal, scopeId)
        }
    }

    private fun loadPrincipal(id: UUID): Principal {
        return principalRepository.findPrincipalById(id)
            ?: throw InternalDataIntegrityException("Principal $id was passed for permission check, but it couldn't be found in the DB")
    }
}