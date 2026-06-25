package io.dockistry.platform.setup.application

import io.dockistry.platform.iam.application.UserService
import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.domain.SystemRoleNames
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.iam.domain.UserCredentialsValidator
import io.dockistry.platform.iam.infrastructure.RoleRepository
import io.dockistry.platform.iam.infrastructure.ScopeRepository
import io.dockistry.platform.setup.domain.InitialAdminAlreadyExistsException
import io.dockistry.platform.setup.infrastructure.InitialAdminRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class InitialAdminService(
    private val initialAdminRepository: InitialAdminRepository,
    private val userService: UserService,
    private val clock: Clock,
    private val scopeRepository: ScopeRepository,
    private val roleRepository: RoleRepository,
) {
    @Transactional
    fun initializeAdmin(username: String, email: String, password: String): User {
        if (initialAdminRepository.existsAdmin()) {
            throw InitialAdminAlreadyExistsException()
        }

        UserCredentialsValidator.validateUsername(username)
        UserCredentialsValidator.validateEmail(email)
        UserCredentialsValidator.validatePassword(password)

        val user = userService.createUser(
            username = username,
            email = email,
            password = password
        ).apply { emailVerifiedAt = clock.instant() }

        user.principal.roleBindings.add(
            RoleBinding(
                subjectPrincipal = user.principal,
                scope = scopeRepository.findByScopeType(ScopeType.GLOBAL).singleOrNull()
                    ?: throw InternalDataIntegrityException("There must be exactly one Scope with GLOBAL type"),
                role = roleRepository.findByNameAndIsSystemTrue(SystemRoleNames.PROJECT_ADMIN)
                    ?: throw InternalDataIntegrityException("System role ${SystemRoleNames.PROJECT_ADMIN} doesn't exist")
            )
        )

        return user
    }

}