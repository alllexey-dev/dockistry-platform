package io.dockistry.platform.service

import io.dockistry.platform.exception.UserAlreadyExistsException
import io.dockistry.platform.model.User
import io.dockistry.platform.repository.UserRepository
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
