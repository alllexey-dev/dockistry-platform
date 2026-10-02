package io.dockistry.platform.secrets.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCallback
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class SecretDelegationLock(
    private val jdbcTemplate: JdbcTemplate
) {
    /** Holds the lock until the caller's transaction commits or rolls back. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lock(key: Long) {
        jdbcTemplate.execute(
            "SELECT pg_advisory_xact_lock(?)"
        ) { statement ->
            statement.setLong(1, key)
            statement.execute()
        }
    }
}
