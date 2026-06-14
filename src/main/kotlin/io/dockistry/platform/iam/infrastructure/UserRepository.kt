package io.dockistry.platform.iam.infrastructure

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface UserRepository : JpaRepository<User, UUID> {
    fun findUserByUsername(username: String): User?
    fun existsUserByUsername(username: String): Boolean
    fun existsUserByEmail(email: String): Boolean
    fun findUserById(id: UUID): User?
}