package io.dockistry.platform.iam.application

import io.dockistry.platform.auth.domain.UserAlreadyExistsException
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.iam.infrastructure.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

@Service
class UserService(
    private val userRepository: UserRepository
) {
    fun insertUser(user: User) {
        throwIfUserExists(user)

        try {
            userRepository.saveAndFlush(user)
        } catch (exception: DataIntegrityViolationException) {
            throwIfUserExists(user)
            throw exception
        }
    }

    private fun throwIfUserExists(user: User) {
        if (userRepository.existsUserByUsername(user.username)) {
            throw UserAlreadyExistsException("username", user.username)
        } else if (userRepository.existsUserByEmail(user.email)) {
            throw UserAlreadyExistsException("email", user.email)
        }
    }
}
