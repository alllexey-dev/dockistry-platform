package io.dockistry.platform.service

import io.dockistry.platform.exception.UserAlreadyExistsException
import io.dockistry.platform.model.User
import io.dockistry.platform.repository.UserRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.dao.DataIntegrityViolationException
import kotlin.test.assertFailsWith

@ExtendWith(MockitoExtension::class)
class UserServiceTests {

    @Mock
    private lateinit var userRepository: UserRepository

    @Test
    fun `insert user converts duplicate username database race to user conflict`() {
        val user = User(
            username = "duplicate-user",
            email = "duplicate-user@dockistry.io",
            passwordHash = "hash"
        )

        Mockito.`when`(userRepository.existsUserByUsername(user.username))
            .thenReturn(false, true)
        Mockito.`when`(userRepository.existsUserByEmail(user.email))
            .thenReturn(false)
        Mockito.`when`(userRepository.saveAndFlush(user))
            .thenThrow(DataIntegrityViolationException("duplicate username"))

        assertFailsWith<UserAlreadyExistsException> {
            UserService(userRepository).insertUser(user)
        }
    }

    @Test
    fun `insert user converts duplicate email database race to user conflict`() {
        val user = User(
            username = "unique-user",
            email = "duplicate-email@dockistry.io",
            passwordHash = "hash"
        )

        Mockito.`when`(userRepository.existsUserByUsername(user.username))
            .thenReturn(false)
        Mockito.`when`(userRepository.existsUserByEmail(user.email))
            .thenReturn(false, true)
        Mockito.`when`(userRepository.saveAndFlush(user))
            .thenThrow(DataIntegrityViolationException("duplicate email"))

        assertFailsWith<UserAlreadyExistsException> {
            UserService(userRepository).insertUser(user)
        }
    }
}
