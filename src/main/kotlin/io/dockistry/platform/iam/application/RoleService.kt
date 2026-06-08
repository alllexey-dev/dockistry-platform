package io.dockistry.platform.iam.application

import io.dockistry.platform.iam.domain.InvalidRoleOwnershipException
import io.dockistry.platform.iam.domain.Role
import io.dockistry.platform.iam.domain.RoleNameCollidesProjectRoleException
import io.dockistry.platform.iam.domain.RoleNameCollidesSystemRoleException
import io.dockistry.platform.iam.infrastructure.RoleRepository
import jakarta.transaction.Transactional
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

@Service
class RoleService(private val roleRepository: RoleRepository) {
    @Transactional
    fun insertRole(role: Role) {
        validateRoleNameAvailability(role)

        try {
            roleRepository.saveAndFlush(role)
        } catch (exception: DataIntegrityViolationException) {
            validateRoleNameAvailability(role)
            throw exception
        }
    }

    private fun validateRoleNameAvailability(role: Role) {
        if (roleRepository.existsByNameAndIsSystemTrue(role.name)) {
            throw RoleNameCollidesSystemRoleException()
        }

        if (!role.isSystem) {
            val project = role.project ?: throw InvalidRoleOwnershipException()

            if (roleRepository.existsByNameAndProject(role.name, project)) {
                throw RoleNameCollidesProjectRoleException()
            }
        }
    }
}
