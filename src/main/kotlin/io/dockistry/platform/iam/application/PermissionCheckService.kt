package io.dockistry.platform.iam.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.infrastructure.PrincipalRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PermissionCheckService(private val principalRepository: PrincipalRepository) {
    @Transactional
    fun hasGlobalPermission(key: String, principalId: UUID): Boolean {
        val principal = loadPrincipal(principalId)
        return principal.roleBindings.any { binding ->
            binding.scope.scopeType == ScopeType.GLOBAL &&
                    binding.role.permissions.any { permission ->
                        permission.key == key
                    }
        }
    }

    @Transactional
    fun hasPermission(key: String, scopeId: UUID, principalId: UUID): Boolean {
        val principal = loadPrincipal(principalId)
        return principal.roleBindings.any { binding ->
            (binding.scope.id == scopeId || binding.scope.scopeType == ScopeType.GLOBAL) &&
                    binding.role.permissions.any { permission ->
                        permission.key == key
                    }
        }
    }

    private fun loadPrincipal(id: UUID): Principal {
        return principalRepository.findPrincipalById(id)
            ?: throw InternalDataIntegrityException("Principal $id was passed for project creation, but it couldn't be found in the DB")
    }
}