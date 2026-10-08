package io.dockistry.platform.projects.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.iam.infrastructure.RoleBindingRepository
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.secrets.application.SecretService
import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import io.dockistry.platform.secrets.domain.SecretDelegationActiveException
import io.dockistry.platform.secrets.infrastructure.ProjectSecretDelegationRepository
import io.dockistry.platform.testsupport.IamProjectFixtures
import io.dockistry.platform.testsupport.SecretDelegationFixtures
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfig::class)
@Testcontainers
@Transactional
class ProjectMemberSecretDelegationIntegrationTests {
    @Autowired private lateinit var memberService: ProjectMemberService
    @Autowired private lateinit var secretService: SecretService
    @Autowired private lateinit var bindingRepository: RoleBindingRepository
    @Autowired private lateinit var delegationRepository: ProjectSecretDelegationRepository
    @PersistenceContext private lateinit var entityManager: EntityManager

    enum class Removal { BINDING, MEMBER }
    enum class Alternative { GROUP, GLOBAL }

    companion object {
        @JvmStatic
        fun removalAlternatives(): List<Arguments> {
            return Removal.entries.flatMap { removal ->
                Alternative.entries.map { alternative -> Arguments.of(removal, alternative) }
            }
        }

        @JvmStatic
        fun removalPrincipalTypes(): List<Arguments> {
            return Removal.entries.flatMap { removal ->
                PrincipalType.entries.map { type -> Arguments.of(removal, type) }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Removal::class)
    fun `user cannot lose last delegate source while owning an unused delegation`(
        removal: Removal
    ) {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = manager(fixtures, project)
        val secretOwner = fixtures.principal()
        val binding = delegateBinding(fixtures, secretOwner, project.scope)
        val readBinding = fixtures.grantSystemPermission(
            secretOwner, project.scope, SystemPermissionNames.PROJECT_READ.key
        )
        val delegation = secrets().delegate(secretOwner, project)
        fixtures.flushAndClear()

        assertBlocked(removal, manager, project, binding, listOf(binding, readBinding),
            listOf(delegation))
    }

    @ParameterizedTest
    @MethodSource("removalAlternatives")
    fun `user delegation permits removal when group or global source remains`(
        removal: Removal,
        alternative: Alternative
    ) {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = manager(fixtures, project)
        val owner = fixtures.user()
        val binding = delegateBinding(fixtures, owner.principal, project.scope)
        val remaining = when (alternative) {
            Alternative.GROUP -> {
                val group = fixtures.group()
                owner.groups += group
                delegateBinding(fixtures, group.principal, project.scope)
            }
            Alternative.GLOBAL ->
                delegateBinding(fixtures, owner.principal, fixtures.globalScope())
        }
        val delegation = secrets().delegate(owner.principal, project)
        fixtures.flushAndClear()

        remove(removal, manager, project, binding)

        assertFalse(bindingRepository.existsById(binding.id))
        assertTrue(bindingRepository.existsById(remaining.id))
        assertTrue(delegationRepository.existsById(delegation.id))
    }

    @ParameterizedTest
    @EnumSource(PrincipalType::class)
    fun `another direct delegate binding permits removal of one binding`(
        type: PrincipalType
    ) {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = manager(fixtures, project)
        val owner = fixtures.principal(type)
        val removed = delegateBinding(fixtures, owner, project.scope)
        val remaining = delegateBinding(fixtures, owner, project.scope)
        val delegation = secrets().delegate(owner, project)
        fixtures.flushAndClear()

        remove(Removal.BINDING, manager, project, removed)

        assertFalse(bindingRepository.existsById(removed.id))
        assertTrue(bindingRepository.existsById(remaining.id))
        assertTrue(delegationRepository.existsById(delegation.id))
    }

    @ParameterizedTest
    @EnumSource(PrincipalType::class)
    fun `member removal excludes all direct delegate bindings together`(
        type: PrincipalType
    ) {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = manager(fixtures, project)
        val owner = fixtures.principal(type)
        val bindings = List(2) { delegateBinding(fixtures, owner, project.scope) }
        val delegation = secrets().delegate(owner, project)
        fixtures.flushAndClear()

        assertBlocked(Removal.MEMBER, manager, project, bindings.first(), bindings,
            listOf(delegation))
    }

    @ParameterizedTest
    @EnumSource(Removal::class)
    fun `group cannot lose last delegate source for its own unused secret`(
        removal: Removal
    ) {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = manager(fixtures, project)
        val group = fixtures.group()
        val binding = delegateBinding(fixtures, group.principal, project.scope)
        val delegation = secrets().delegate(group.principal, project)
        fixtures.flushAndClear()

        assertBlocked(removal, manager, project, binding, listOf(binding),
            listOf(delegation))
    }

    @ParameterizedTest
    @EnumSource(Removal::class)
    fun `group keeps its own delegation when global delegate source remains`(
        removal: Removal
    ) {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = manager(fixtures, project)
        val group = fixtures.group()
        val binding = delegateBinding(fixtures, group.principal, project.scope)
        val global = delegateBinding(fixtures, group.principal, fixtures.globalScope())
        val delegation = secrets().delegate(group.principal, project)
        fixtures.flushAndClear()

        remove(removal, manager, project, binding)

        assertFalse(bindingRepository.existsById(binding.id))
        assertTrue(bindingRepository.existsById(global.id))
        assertTrue(delegationRepository.existsById(delegation.id))
    }

    @ParameterizedTest
    @EnumSource(Removal::class)
    fun `one group user without direct membership blocks the complete removal`(
        removal: Removal
    ) {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = manager(fixtures, project)
        val group = fixtures.group()
        val safeOwner = fixtures.user().apply { groups += group }
        val conflictingOwner = fixtures.user().apply { groups += group }
        val bindings = listOf(
            delegateBinding(fixtures, group.principal, project.scope),
            fixtures.grantSystemPermission(
                group.principal, project.scope, SystemPermissionNames.PROJECT_READ.key
            )
        )
        val safeBinding = delegateBinding(fixtures, safeOwner.principal, project.scope)
        delegateBinding(fixtures, conflictingOwner.principal, fixtures.project().scope)
        val delegations = listOf(safeOwner, conflictingOwner).map {
            secrets().delegate(it.principal, project)
        }
        fixtures.flushAndClear()

        assertTrue(bindingRepository.findByScopeAndSubjectPrincipalId(
            project.scope, conflictingOwner.id
        ).isEmpty())
        assertBlocked(removal, manager, project, bindings.first(),
            bindings + safeBinding, delegations)
    }

    @ParameterizedTest
    @EnumSource(Removal::class)
    fun `all delegated group users can retain permissions through different sources`(
        removal: Removal
    ) {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = manager(fixtures, project)
        val group = fixtures.group()
        val binding = delegateBinding(fixtures, group.principal, project.scope)
        val owners = List(3) { fixtures.user().apply { groups += group } }
        fixtures.user().apply { groups += group } // No delegation or alternative source.
        val otherGroup = fixtures.group()
        owners[1].groups += otherGroup
        val alternatives = listOf(
            delegateBinding(fixtures, owners[0].principal, project.scope),
            delegateBinding(fixtures, otherGroup.principal, project.scope),
            delegateBinding(fixtures, owners[2].principal, fixtures.globalScope())
        )
        val delegations = owners.map { secrets().delegate(it.principal, project) }
        fixtures.flushAndClear()

        remove(removal, manager, project, binding)

        assertFalse(bindingRepository.existsById(binding.id))
        alternatives.forEach { assertTrue(bindingRepository.existsById(it.id)) }
        delegations.forEach { assertTrue(delegationRepository.existsById(it.id)) }
    }

    @ParameterizedTest
    @MethodSource("removalPrincipalTypes")
    fun `delegations to another project do not block binding or member removal`(
        removal: Removal,
        type: PrincipalType
    ) {
        val fixtures = fixtures()
        val project = fixtures.project()
        val otherProject = fixtures.project()
        val manager = manager(fixtures, project)
        val owner = fixtures.principal(type)
        val binding = delegateBinding(fixtures, owner, project.scope)
        val otherBinding = delegateBinding(fixtures, owner, otherProject.scope)
        val delegation = secrets().delegate(owner, otherProject)
        fixtures.flushAndClear()

        remove(removal, manager, project, binding)

        assertFalse(bindingRepository.existsById(binding.id))
        assertTrue(bindingRepository.existsById(otherBinding.id))
        assertTrue(delegationRepository.existsById(delegation.id))
    }

    @ParameterizedTest
    @EnumSource(Removal::class)
    fun `owner revoke removes the blocker before permission removal`(removal: Removal) {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = manager(fixtures, project)
        val owner = fixtures.principal()
        val binding = delegateBinding(fixtures, owner, project.scope)
        val delegation = secrets().delegate(owner, project)
        fixtures.flushAndClear()

        secretService.revoke(delegation.secret.id, project.id, owner)
        remove(removal, manager, project, binding)

        assertFalse(bindingRepository.existsById(binding.id))
        assertFalse(delegationRepository.existsById(delegation.id))
    }

    private fun assertBlocked(
        removal: Removal,
        manager: Principal,
        project: Project,
        removed: RoleBinding,
        retained: List<RoleBinding>,
        delegations: List<ProjectSecretDelegation>
    ) {
        val exception = assertFailsWith<SecretDelegationActiveException> {
            remove(removal, manager, project, removed)
        }
        assertEquals("secret_delegation_active", exception.code)
        retained.forEach { assertTrue(bindingRepository.existsById(it.id)) }
        delegations.forEach { assertTrue(delegationRepository.existsById(it.id)) }
    }

    private fun remove(
        removal: Removal,
        manager: Principal,
        project: Project,
        binding: RoleBinding
    ) {
        when (removal) {
            Removal.BINDING -> memberService.deleteRoleBinding(
                manager, project.id, binding.subjectPrincipal.id, binding.id
            )
            Removal.MEMBER -> memberService.deleteMember(
                manager, project.id, binding.subjectPrincipal.id
            )
        }
    }

    private fun fixtures() = IamProjectFixtures(entityManager)
    private fun secrets() = SecretDelegationFixtures(entityManager)

    private fun manager(fixtures: IamProjectFixtures, project: Project): Principal {
        val manager = fixtures.principal()
        fixtures.grantSystemPermission(manager, project.scope,
            SystemPermissionNames.PROJECT_READ.key,
            SystemPermissionNames.PROJECT_MEMBERS_MANAGE.key)
        return manager
    }

    private fun delegateBinding(
        fixtures: IamProjectFixtures,
        principal: Principal,
        scope: Scope
    ): RoleBinding = fixtures.grantSystemPermission(principal, scope,
        SystemPermissionNames.PROJECT_READ.key,
        SystemPermissionNames.SECRETS_DELEGATE.key)
}
