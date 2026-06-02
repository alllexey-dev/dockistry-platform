package io.dockistry.platform.service

import io.dockistry.platform.model.Principal
import io.dockistry.platform.model.PrincipalType
import io.dockistry.platform.model.RefreshToken
import io.dockistry.platform.model.User
import io.dockistry.platform.repository.RefreshTokenRepository
import io.dockistry.platform.repository.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@SpringBootTest
class RefreshTokenOptimisticLockingTests {

    @Autowired
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `stale refresh token revocation fails with optimistic locking`() {
        val tokenId = createRefreshToken()

        val firstCopy = inNewTransaction {
            refreshTokenRepository.findRefreshTokenById(tokenId)!!
        }
        val staleSecondCopy = inNewTransaction {
            refreshTokenRepository.findRefreshTokenById(tokenId)!!
        }

        inNewTransaction {
            firstCopy.revokedAt = Instant.now()
            refreshTokenRepository.saveAndFlush(firstCopy)
        }

        assertFailsWith<OptimisticLockingFailureException> {
            inNewTransaction {
                staleSecondCopy.revokedAt = Instant.now()
                refreshTokenRepository.saveAndFlush(staleSecondCopy)
            }
        }

        val savedToken = inNewTransaction {
            refreshTokenRepository.findRefreshTokenById(tokenId)!!
        }
        assertNotNull(savedToken.revokedAt)
        assertEquals(1, savedToken.version)
    }

    private fun createRefreshToken(): UUID {
        val suffix = UUID.randomUUID()
        val tokenId = UUID.randomUUID()

        inNewTransaction {
            val user = userRepository.save(
                User(
                    username = "refresh-lock-$suffix",
                    email = "refresh-lock-$suffix@dockistry.io",
                    passwordHash = "hash"
                ).apply {
                    principal = Principal(PrincipalType.USER)
                }
            )

            refreshTokenRepository.saveAndFlush(
                RefreshToken(
                    id = tokenId,
                    issuedAt = Instant.now(),
                    expiresAt = Instant.now().plusSeconds(60)
                ).apply {
                    this.user = user
                }
            )
        }

        return tokenId
    }

    private fun <T> inNewTransaction(action: () -> T): T {
        val transactionTemplate = TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

        return transactionTemplate.execute { action() }!!
    }
}
