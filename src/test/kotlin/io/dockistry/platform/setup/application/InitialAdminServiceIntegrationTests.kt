package io.dockistry.platform.setup.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.InvalidEmailException
import io.dockistry.platform.iam.domain.InvalidPasswordException
import io.dockistry.platform.iam.domain.InvalidUsernameException
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.domain.SystemRoleNames
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.iam.infrastructure.RoleRepository
import io.dockistry.platform.iam.infrastructure.ScopeRepository
import io.dockistry.platform.iam.infrastructure.UserRepository
import io.dockistry.platform.setup.domain.InitialAdminAlreadyExistsException
import io.dockistry.platform.setup.infrastructure.InitialAdminRepository
import io.dockistry.platform.shared.domain.SystemSettingKeys
import io.dockistry.platform.shared.infrastructure.SystemSettingRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfig::class)
@Testcontainers
@Transactional
class InitialAdminServiceIntegrationTests {
    @Autowired
    private lateinit var scopeRepository: ScopeRepository

    @Autowired
    private lateinit var initialAdminService: InitialAdminService

    @Autowired
    private lateinit var initialAdminRepository: InitialAdminRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var roleRepository: RoleRepository

    @Autowired
    private lateinit var systemSettingRepository: SystemSettingRepository

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `initialize admin throws already exists exception when initial admin exists`() {
        persistExistingInitialAdmin(
            username = "existing-admin",
            email = "existing-admin@dockistry.io"
        )

        assertFailsWith<InitialAdminAlreadyExistsException> {
            initialAdminService.initializeAdmin(
                username = "another-admin",
                email = "another-admin@dockistry.io",
                password = "password-123"
            )
        }
    }

    @Test
    fun `initialize admin rejects invalid credentials with matching domain exceptions`() {
        assertFailsWith<InvalidUsernameException> {
            initialAdminService.initializeAdmin(
                username = "ab",
                email = "valid-admin@dockistry.io",
                password = "password-123"
            )
        }

        assertFailsWith<InvalidEmailException> {
            initialAdminService.initializeAdmin(
                username = "valid-admin",
                email = "not-an-email",
                password = "password-123"
            )
        }

        assertFailsWith<InvalidPasswordException> {
            initialAdminService.initializeAdmin(
                username = "valid-admin",
                email = "valid-admin@dockistry.io",
                password = "short"
            )
        }
    }

    @Test
    fun `initialize admin creates verified global project admin and marks setup complete`() {
        val admin = initialAdminService.initializeAdmin(
            username = "initial-admin",
            email = "initial-admin@dockistry.io",
            password = "password-123"
        )
        entityManager.flush()
        entityManager.clear()

        val persistedAdmin = assertNotNull(userRepository.findUserByUsername("initial-admin"))
        assertEquals(admin.id, persistedAdmin.id)
        assertEquals("initial-admin@dockistry.io", persistedAdmin.email)
        assertNotNull(persistedAdmin.emailVerifiedAt)

        val binding = persistedAdmin.principal.roleBindings.single()
        assertEquals(ScopeType.GLOBAL, binding.scope.scopeType)
        assertEquals(SystemRoleNames.PROJECT_ADMIN, binding.role.name)

        assertTrue(
            systemSettingRepository.existsById(
                SystemSettingKeys.INITIAL_ADMIN_BOOTSTRAP_EXECUTED
            )
        )
        assertTrue(initialAdminRepository.existsAdmin())
    }

    private fun persistExistingInitialAdmin(username: String, email: String) {
        val globalScope = scopeRepository.findByScopeType(ScopeType.GLOBAL).single()
        val adminRole = assertNotNull(
            roleRepository.findByNameAndIsSystemTrue(SystemRoleNames.PROJECT_ADMIN)
        )
        val user = User(
            username = username,
            email = email,
            passwordHash = "hashed-password"
        ).apply {
            principal = Principal(PrincipalType.USER)
            emailVerifiedAt = Instant.parse("2026-01-01T00:00:00Z")
        }

        entityManager.persist(user)
        entityManager.persist(
            RoleBinding(
                subjectPrincipal = user.principal,
                scope = globalScope,
                role = adminRole
            )
        )
        entityManager.flush()
    }
}
