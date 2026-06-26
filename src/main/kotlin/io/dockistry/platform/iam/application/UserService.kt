package io.dockistry.platform.iam.application

import io.dockistry.platform.auth.domain.UserAlreadyExistsException
import io.dockistry.platform.auth.domain.UserHasNotVerifiedEmailException
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.iam.domain.UserCredentialsValidator
import io.dockistry.platform.iam.infrastructure.UserRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder
) {
    @Transactional
    fun createUser(username: String, email: String, password: String): User {
        val password = UserCredentialsValidator.validatePassword(password)

        val user = User(
            email = email,
            username = username,
            passwordHash = requireNotNull(
                passwordEncoder.encode(password)
            ) { "PasswordEncoder returned null for non-null password" }
        ).apply {
            principal = Principal(PrincipalType.USER)
        }

        insertUser(user)
        return user
    }

    @Transactional
    fun updateUser(
        userId: UUID,
        username: String,
        email: String,
        password: String
    ): User {
        val password = UserCredentialsValidator.validatePassword(password)

        val user = userRepository.findUserById(userId)
            ?: throw InternalDataIntegrityException("User ID $userId was passed for updating, but User couldn't be found in DB")

        throwIfUserUpdateConflicts(userId, username, email)

        user.username = username
        user.email = email
        user.passwordHash = requireNotNull(passwordEncoder.encode(password)) {
            "Password Encoder returned null for non-null password"
        }

        return try {
            userRepository.saveAndFlush(user)
        } catch (exception: DataIntegrityViolationException) {
            throwIfUserUpdateConflicts(userId, username, email)
            throw exception
        }
    }

    fun insertUser(user: User) {
        throwIfUserExists(user)

        try {
            userRepository.saveAndFlush(user)
        } catch (exception: DataIntegrityViolationException) {
            throwIfUserExists(user)
            throw exception
        }
    }

    fun hasVerifiedEmail(username: String) {
        val user = userRepository.findUserByUsername(username)
            ?: throw InternalDataIntegrityException("Username $username was passed for verified email check, but couldn't be found in DB")

        if (!hasVerifiedEmail(user)) {
            throw UserHasNotVerifiedEmailException()
        }
    }

    fun hasVerifiedEmail(user: User): Boolean {
        return user.emailVerifiedAt != null
    }

    private fun throwIfUserExists(user: User) {
        if (userRepository.existsUserByUsername(user.username)) {
            throw UserAlreadyExistsException("username", user.username)
        } else if (userRepository.existsUserByEmail(user.email)) {
            throw UserAlreadyExistsException("email", user.email)
        }
    }

    private fun throwIfUserUpdateConflicts(userId: UUID, username: String, email: String) {
        val usernameOwner = userRepository.findUserByUsername(username)
        if (usernameOwner != null && usernameOwner.id != userId) {
            throw UserAlreadyExistsException("username", username)
        }

        val emailOwner = userRepository.findByEmail(email)
        if (emailOwner != null && emailOwner.id != userId) {
            throw UserAlreadyExistsException("email", email)
        }
    }
}
