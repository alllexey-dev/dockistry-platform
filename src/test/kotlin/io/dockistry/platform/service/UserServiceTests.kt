package io.dockistry.platform.service

import io.dockistry.platform.auth.domain.UserAlreadyExistsException
import io.dockistry.platform.iam.application.UserService
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.iam.infrastructure.UserRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import kotlin.test.assertFailsWith

@ExtendWith(MockitoExtension::class)
class UserServiceTests {

    @Mock
    private lateinit var userRepository: UserRepository

    private val unusedPasswordEncoder = object : PasswordEncoder {
        override fun encode(rawPassword: CharSequence?) =
            error("PasswordEncoder should not be used in this test")

        override fun matches(rawPassword: CharSequence?, encodedPassword: String?) =
            error("PasswordEncoder should not be used in this test")
    }

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
            UserService(userRepository, unusedPasswordEncoder).insertUser(user)
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
            UserService(userRepository, unusedPasswordEncoder).insertUser(user)
        }
    }
}
