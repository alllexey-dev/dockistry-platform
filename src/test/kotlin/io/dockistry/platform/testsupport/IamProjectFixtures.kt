package io.dockistry.platform.testsupport

import io.dockistry.platform.iam.domain.Group
import io.dockistry.platform.iam.domain.Permission
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.Role
import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.projects.domain.Project
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID

class IamProjectFixtures(
    private val entityManager: EntityManager
) {
    fun principal(type: PrincipalType = PrincipalType.USER): Principal {
        val principal = Principal(type)
        entityManager.persist(principal)

        when (type) {
            PrincipalType.USER -> persistUser(principal)
            PrincipalType.GROUP -> persistGroup(principal)
        }

        return principal
    }

    fun user(
        principal: Principal = Principal(PrincipalType.USER),
        username: String = "test-user-${unique()}",
        email: String = "$username@dockistry.io",
        passwordHash: String = "hashed-password"
    ): User {
        return persistUser(principal, username, email, passwordHash)
    }

    fun group(
        principal: Principal = Principal(PrincipalType.GROUP),
        name: String = "test-group-${unique()}",
    ): Group {
        val group = Group(name).apply {
            this.principal = principal
        }
        entityManager.persist(group)
        return group
    }

    private fun persistUser(
        principal: Principal,
        username: String = "test-user-${unique()}",
        email: String = "$username@dockistry.io",
        passwordHash: String = "hashed-password"
    ): User {
        val user = User(username, email, passwordHash).apply {
            this.principal = principal
        }
        entityManager.persist(user)
        return user
    }

    private fun persistGroup(
        principal: Principal,
        name: String = "test-group-${unique()}",
    ): Group {
        val group = Group(name).apply {
            this.principal = principal
        }
        entityManager.persist(group)
        return group
    }

    fun globalScope(): Scope {
        val existing = entityManager
            .createQuery(
                "select scope from Scope scope where scope.scopeType = :scopeType",
                Scope::class.java
            )
            .setParameter("scopeType", ScopeType.GLOBAL)
            .resultList
            .firstOrNull()

        if (existing != null) {
            return existing
        }

        return scope(ScopeType.GLOBAL)
    }

    fun scope(scopeType: ScopeType = ScopeType.PROJECT): Scope {
        val scope = Scope(scopeType)
        entityManager.persist(scope)
        return scope
    }

    fun permission(key: String = "test.permission.${unique()}"): Permission {
        val permission = Permission(key, "Test permission $key")
        entityManager.persist(permission)
        return permission
    }

    fun systemPermission(key: String): Permission {
        return entityManager
            .createQuery(
                "select permission from Permission permission where permission.key = :key",
                Permission::class.java
            )
            .setParameter("key", key)
            .singleResult
    }

    fun role(
        name: String = "test-role-${unique()}",
        permissions: Set<Permission> = emptySet(),
        isSystem: Boolean = true,
        project: Project? = null
    ): Role {
        val role = Role(
            name = name,
            description = "Test role $name",
            isSystem = isSystem,
            project = project
        )
        role.permissions.addAll(permissions)
        entityManager.persist(role)
        return role
    }

    fun bind(principal: Principal, scope: Scope, role: Role): RoleBinding {
        val binding = RoleBinding(principal, scope, role)
        principal.roleBindings.add(binding)
        scope.roleBindings.add(binding)
        role.roleBindings.add(binding)
        entityManager.persist(binding)
        return binding
    }

    fun grant(principal: Principal, scope: Scope, vararg permissionKeys: String): RoleBinding {
        val permissions = permissionKeys.map { permission(it) }.toSet()
        val role = role(permissions = permissions)
        return bind(principal, scope, role)
    }

    fun grantSystemPermission(principal: Principal, scope: Scope, vararg permissionKeys: String): RoleBinding {
        val permissions = permissionKeys.map { systemPermission(it) }.toSet()
        val role = role(permissions = permissions)
        return bind(principal, scope, role)
    }

    fun project(
        name: String = "Project ${unique()}",
        slug: String = "project-${unique()}",
        ownerPrincipal: Principal = principal(),
        createdAt: Instant = Instant.parse("2026-01-01T00:00:00Z")
    ): Project {
        val project = Project(
            name = name,
            slug = slug,
            ownerPrincipal = ownerPrincipal,
            createdAt = createdAt,
            scope = Scope(ScopeType.PROJECT)
        )
        entityManager.persist(project)
        return project
    }

    fun flushAndClear() {
        entityManager.flush()
        entityManager.clear()
    }

    fun flush() {
        entityManager.flush()
    }

    fun unique(): String = UUID.randomUUID().toString().replace("-", "").take(12)
}
