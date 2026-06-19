package io.dockistry.platform.auth.application

import io.dockistry.platform.auth.domain.EmailVerificationChallengeReason
import io.dockistry.platform.auth.domain.UserAlreadyExistsException
import io.dockistry.platform.auth.domain.dto.RegisterRequest
import io.dockistry.platform.iam.application.UserService
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.iam.infrastructure.UserRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RegistrationService(
    private val userService: UserService,
    private val userRepository: UserRepository,
    private val emailVerificationFlowService: EmailVerificationFlowService
) {
    @Transactional
    fun registerUser(registerRequest: RegisterRequest) {
        ensureUserInDb(
            username = registerRequest.username,
            email = registerRequest.email,
            password = registerRequest.password
        )

        emailVerificationFlowService.requestVerificationCodes(
            registerRequest.email,
            EmailVerificationChallengeReason.REGISTRATION
        )
    }

    private fun ensureUserInDb(username: String, email: String, password: String): User {
        return if (userRepository.existsUserByEmail(email)) {
            val user = userRepository.findByEmail(email)
                ?: throw InternalDataIntegrityException("User with email $email was just found with exists method, but couldn't be found in DB as an entity")

            if (user.emailVerifiedAt != null) {
                throw UserAlreadyExistsException("email", email)
            }

            userService.updateUser(user.id, username, email, password)
        } else {
            userService.createUser(
                username = username,
                email = email,
                password = password
            )
        }
    }
}
