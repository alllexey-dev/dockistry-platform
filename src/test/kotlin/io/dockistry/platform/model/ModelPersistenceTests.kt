package io.dockistry.platform.model

import io.dockistry.platform.iam.domain.Group
import io.dockistry.platform.iam.domain.Permission
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.Role
import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.TestcontainersConfig
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Test
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@DataJpaTest
@Import(TestcontainersConfig::class)
@Testcontainers
class ModelPersistenceTests {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `users and groups persist with principal-backed ids and membership`() {
        val userPrincipal = Principal(PrincipalType.USER)
        val groupPrincipal = Principal(PrincipalType.GROUP)
        entityManager.persist(userPrincipal)
        entityManager.persist(groupPrincipal)

        val group = Group("platform-operators").apply {
            principal = groupPrincipal
        }
        val user = User("deploy-admin", "deploy-admin@dockistry.io", "hashed-password").apply {
            principal = userPrincipal
            groups += group
        }
        entityManager.persist(group)
        entityManager.persist(user)
        entityManager.flush()

        val userId = user.id
        val groupId = group.id
        entityManager.clear()

        val savedUser = entityManager.find(User::class.java, userId)
        val savedGroup = entityManager.find(Group::class.java, groupId)

        assertNotNull(savedUser)
        assertNotNull(savedGroup)
        assertEquals(userId, savedUser.principal.id)
        assertEquals(PrincipalType.USER, savedUser.principal.principalType)
        assertEquals(setOf(groupId), savedUser.groups.map { it.id }.toSet())
        assertEquals(groupId, savedGroup.principal.id)
        assertEquals(setOf(userId), savedGroup.members.map { it.id }.toSet())
    }

    @Test
    fun `roles permissions and scoped role bindings persist together`() {
        val principal = Principal(PrincipalType.USER)
        val scope = Scope(ScopeType.PROJECT)
        val permission = Permission("deploy.release", "Deploy a release")
        val role = Role("deployer", "Can deploy releases", true).apply {
            permissions += permission
        }
        entityManager.persist(principal)
        entityManager.persist(scope)
        entityManager.persist(permission)
        entityManager.persist(role)

        val roleBinding = RoleBinding(principal, scope, role)
        entityManager.persist(roleBinding)
        entityManager.flush()

        val principalId = principal.id
        val roleId = role.id
        val bindingId = roleBinding.id
        entityManager.clear()

        val savedRole = entityManager.find(Role::class.java, roleId)
        val savedBinding = entityManager.find(RoleBinding::class.java, bindingId)
        val savedPrincipal = entityManager.find(Principal::class.java, principalId)

        assertNotNull(savedRole)
        assertNotNull(savedBinding)
        assertNotNull(savedPrincipal)
        assertEquals(setOf("deploy.release"), savedRole.permissions.map { it.key }.toSet())
        assertEquals(ScopeType.PROJECT, savedBinding.scope.scopeType)
        assertEquals(roleId, savedBinding.role.id)
        assertEquals(principalId, savedBinding.subjectPrincipal.id)
        assertEquals(setOf(bindingId), savedPrincipal.roleBindings.map { it.id }.toSet())
    }

    @Test
    fun `database allows only one global scope`() {
        entityManager.persist(Scope(ScopeType.GLOBAL))
        entityManager.persist(Scope(ScopeType.GLOBAL))

        assertFailsWith<RuntimeException> {
            entityManager.flush()
        }
    }
}
