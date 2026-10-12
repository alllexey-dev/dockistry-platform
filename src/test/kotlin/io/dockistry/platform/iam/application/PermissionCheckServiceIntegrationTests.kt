package io.dockistry.platform.iam.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfig::class)
@Testcontainers
@Transactional
class PermissionCheckServiceIntegrationTests {
    @Autowired
    private lateinit var permissionCheckService: PermissionCheckService

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `has global permission returns true only for matching permission on global scope`() {
        val fixtures = IamProjectFixtures(entityManager)
        val principal = fixtures.principal()
        fixtures.grantSystemPermission(principal, fixtures.globalScope(), SystemPermissionNames.PROJECT_CREATE.key)
        fixtures.flush()

        assertTrue(permissionCheckService.hasGlobalPermission(SystemPermissionNames.PROJECT_CREATE.key, principal.id))
        assertFalse(permissionCheckService.hasGlobalPermission(SystemPermissionNames.PROJECT_READ.key, principal.id))
    }

    @Test
    fun `has global permission ignores project scoped binding`() {
        val fixtures = IamProjectFixtures(entityManager)
        val principal = fixtures.principal()
        fixtures.grantSystemPermission(principal, fixtures.scope(ScopeType.PROJECT), SystemPermissionNames.PROJECT_CREATE.key)
        fixtures.flush()

        assertFalse(permissionCheckService.hasGlobalPermission(SystemPermissionNames.PROJECT_CREATE.key, principal.id))
    }

    @Test
    fun `has permission returns true for matching permission in exact scope`() {
        val fixtures = IamProjectFixtures(entityManager)
        val principal = fixtures.principal()
        val projectScope = fixtures.scope(ScopeType.PROJECT)
        fixtures.grantSystemPermission(principal, projectScope, SystemPermissionNames.PROJECT_READ.key)
        fixtures.flush()

        assertTrue(permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_READ.key,
            principal.id,
            projectScope.id
        ))
    }

    @Test
    fun `has permission returns true for global permission against concrete scope`() {
        val fixtures = IamProjectFixtures(entityManager)
        val principal = fixtures.principal()
        val projectScope = fixtures.scope(ScopeType.PROJECT)
        fixtures.grantSystemPermission(principal, fixtures.globalScope(), SystemPermissionNames.PROJECT_READ.key)
        fixtures.flush()

        assertTrue(permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_READ.key,
            principal.id,
            projectScope.id
        ))
    }

    @Test
    fun `has permission returns false for different scope or missing permission`() {
        val fixtures = IamProjectFixtures(entityManager)
        val principal = fixtures.principal()
        val grantedScope = fixtures.scope(ScopeType.PROJECT)
        val otherScope = fixtures.scope(ScopeType.PROJECT)
        fixtures.grantSystemPermission(principal, grantedScope, SystemPermissionNames.PROJECT_READ.key)
        fixtures.flush()

        assertFalse(permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_READ.key,
            principal.id,
            otherScope.id
        ))
        assertFalse(permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_UPDATE.key,
            principal.id,
            grantedScope.id
        ))
    }

    @Test
    fun `permission checks throw internal data integrity exception when principal is missing`() {
        assertFailsWith<InternalDataIntegrityException> {
            permissionCheckService.hasGlobalPermission(SystemPermissionNames.PROJECT_READ.key, UUID.randomUUID())
        }
    }

    enum class RemainingSource {
        DIRECT, GROUP, GLOBAL, GROUP_GLOBAL, OTHER_PROJECT, OTHER_PERMISSION,
        OTHER_PRINCIPAL
    }

    @ParameterizedTest
    @EnumSource(RemainingSource::class)
    fun `excluded binding checks recognize only effective remaining sources`(
        source: RemainingSource
    ) {
        val fixtures = IamProjectFixtures(entityManager)
        val user = fixtures.user()
        val fakeProjectScope = fixtures.scope()
        val key = SystemPermissionNames.SECRETS_DELEGATE.key
        val removed =
            fixtures.grantSystemPermission(user.principal, fakeProjectScope, key)

        when (source) {
            RemainingSource.DIRECT -> fixtures.grantSystemPermission(
                user.principal,
                fakeProjectScope,
                key
            )
            RemainingSource.GROUP, RemainingSource.GROUP_GLOBAL -> {
                val group = fixtures.group()
                user.groups += group
                val grantScope = if (source == RemainingSource.GROUP_GLOBAL) {
                    fixtures.globalScope()
                } else fakeProjectScope
                fixtures.grantSystemPermission(group.principal, grantScope, key)
            }
            RemainingSource.GLOBAL ->
                fixtures.grantSystemPermission(user.principal, fixtures.globalScope(), key)
            RemainingSource.OTHER_PROJECT ->
                fixtures.grantSystemPermission(user.principal, fixtures.scope(), key)
            RemainingSource.OTHER_PERMISSION -> fixtures.grantSystemPermission(
                user.principal, fakeProjectScope, SystemPermissionNames.SECRETS_USE.key
            )
            RemainingSource.OTHER_PRINCIPAL ->
                fixtures.grantSystemPermission(fixtures.principal(), fakeProjectScope, key)
        }
        fixtures.flushAndClear()

        val expected = source in setOf(
            RemainingSource.DIRECT, RemainingSource.GROUP,
            RemainingSource.GLOBAL, RemainingSource.GROUP_GLOBAL
        )
        assertEquals(
            expected,
            hasPermissionExcludingBindings(
                key, user.id, fakeProjectScope.id, listOf(removed.id)
            )
        )
        assertEquals(
            if (expected) emptyList() else listOf(user.id),
            permissionCheckService.findPrincipalIdsWithoutPermissionExcludingBindings(
                key, listOf(user.id), fakeProjectScope.id, listOf(removed.id)
            )
        )
    }

    @Test
    fun `excluding the complete binding set removes the last permission source`() {
        val fixtures = IamProjectFixtures(entityManager)
        val principal = fixtures.principal()
        val scope = fixtures.scope()
        val key = SystemPermissionNames.SECRETS_DELEGATE.key
        val bindings = List(2) {
            fixtures.grantSystemPermission(principal, scope, key)
        }
        fixtures.flushAndClear()

        assertTrue(hasPermissionExcludingBindings(
            key, principal.id, scope.id, listOf(bindings.first().id)
        ))
        assertTrue(hasPermissionExcludingBindings(
            key, principal.id, scope.id, listOf(bindings.drop(1).first().id)
        ))
        assertFalse(hasPermissionExcludingBindings(
            key, principal.id, scope.id, bindings.map { it.id }
        ))
        assertEquals(listOf(principal.id),
            permissionCheckService.findPrincipalIdsWithoutPermissionExcludingBindings(
                key, listOf(principal.id), scope.id, bindings.map { it.id }
            )
        )
    }

    @Test
    fun `batch check returns conflicting user ids instead of their group ids`() {
        val fixtures = IamProjectFixtures(entityManager)
        val scope = fixtures.scope()
        val key = SystemPermissionNames.SECRETS_DELEGATE.key
        val removedGroup = fixtures.group()
        val conflictUser = fixtures.user().apply { groups += removedGroup }
        val alternativeGroup = fixtures.group()
        val groupUser = fixtures.user().apply {
            groups += removedGroup
            groups += alternativeGroup
        }
        val directUser = fixtures.user().apply { groups += removedGroup }
        val globalUser = fixtures.user().apply { groups += removedGroup }
        val noBindings = fixtures.principal()
        val otherProject = fixtures.principal()
        val removed = fixtures.grantSystemPermission(removedGroup.principal, scope, key)
        fixtures.grantSystemPermission(alternativeGroup.principal, scope, key)
        fixtures.grantSystemPermission(directUser.principal, scope, key)
        fixtures.grantSystemPermission(globalUser.principal, fixtures.globalScope(), key)
        fixtures.grantSystemPermission(otherProject, fixtures.scope(), key)
        fixtures.flushAndClear()

        assertEquals(setOf(conflictUser.id, noBindings.id, otherProject.id),
            permissionCheckService.findPrincipalIdsWithoutPermissionExcludingBindings(
                key,
                listOf(conflictUser.id, groupUser.id, directUser.id, globalUser.id,
                    noBindings.id, otherProject.id),
                scope.id,
                listOf(removed.id)
            ).toSet()
        )
    }

    @Test
    fun `empty exclusions preserve ordinary permission semantics`() {
        val fixtures = IamProjectFixtures(entityManager)
        val scope = fixtures.scope()
        val principal = fixtures.principal()
        val noPermission = fixtures.principal()
        val key = SystemPermissionNames.SECRETS_DELEGATE.key
        fixtures.grantSystemPermission(principal, scope, key)
        fixtures.flushAndClear()

        assertTrue(hasPermissionExcludingBindings(
            key, principal.id, scope.id, emptyList()
        ))
        assertFalse(hasPermissionExcludingBindings(
            key, noPermission.id, scope.id, emptyList()
        ))
        assertEquals(listOf(noPermission.id),
            permissionCheckService.findPrincipalIdsWithoutPermissionExcludingBindings(
                key, listOf(principal.id, noPermission.id), scope.id, emptyList()
            )
        )
        assertEquals(emptyList(),
            permissionCheckService.findPrincipalIdsWithoutPermissionExcludingBindings(
                key, emptyList(), scope.id, emptyList()
            )
        )
    }

    @Test
    fun `exclusion checks reject missing principals including one in a mixed batch`() {
        val fixtures = IamProjectFixtures(entityManager)
        val principal = fixtures.principal()
        val scope = fixtures.scope()
        val key = SystemPermissionNames.SECRETS_DELEGATE.key
        val binding = fixtures.grantSystemPermission(principal, scope, key)
        fixtures.flushAndClear()
        val missing = UUID.randomUUID()

        assertFailsWith<InternalDataIntegrityException> {
            hasPermissionExcludingBindings(
                key, missing, scope.id, listOf(binding.id)
            )
        }
        assertFailsWith<InternalDataIntegrityException> {
            hasPermissionExcludingBindings(
                key, missing, scope.id, emptyList()
            )
        }
        assertFailsWith<InternalDataIntegrityException> {
            permissionCheckService.findPrincipalIdsWithoutPermissionExcludingBindings(
                key, listOf(principal.id, missing), scope.id, listOf(binding.id)
            )
        }
    }

    private fun hasPermissionExcludingBindings(
        key: String,
        principalId: UUID,
        scopeId: UUID,
        excludedBindingIds: List<UUID>
    ): Boolean = permissionCheckService.findPrincipalIdsWithoutPermissionExcludingBindings(
        key, listOf(principalId), scopeId, excludedBindingIds
    ).isEmpty()
}
