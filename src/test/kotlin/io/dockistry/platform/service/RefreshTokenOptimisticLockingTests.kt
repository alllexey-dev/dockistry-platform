package io.dockistry.platform.service

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.auth.domain.RefreshToken
import io.dockistry.platform.auth.infrastructure.RefreshTokenRepository
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.iam.infrastructure.UserRepository
import io.dockistry.platform.testsupport.concurrency.ConcurrencyTestConfiguration
import io.dockistry.platform.testsupport.concurrency.ConcurrencyTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@SpringBootTest
@Import(TestcontainersConfig::class, ConcurrencyTestConfiguration::class)
@Testcontainers
class RefreshTokenOptimisticLockingTests {

    @Autowired
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var concurrency: ConcurrencyTestSupport

    @Test
    fun `stale refresh token revocation fails with optimistic locking`() {
        val tokenId = createRefreshToken()

        val firstCopy = concurrency.inNewTransaction {
            refreshTokenRepository.findRefreshTokenById(tokenId)!!
        }
        val staleSecondCopy = concurrency.inNewTransaction {
            refreshTokenRepository.findRefreshTokenById(tokenId)!!
        }

        concurrency.inNewTransaction {
            firstCopy.revokedAt = Instant.now()
            refreshTokenRepository.saveAndFlush(firstCopy)
        }

        assertFailsWith<OptimisticLockingFailureException> {
            concurrency.inNewTransaction {
                staleSecondCopy.revokedAt = Instant.now()
                refreshTokenRepository.saveAndFlush(staleSecondCopy)
            }
        }

        val savedToken = concurrency.inNewTransaction {
            refreshTokenRepository.findRefreshTokenById(tokenId)!!
        }
        assertNotNull(savedToken.revokedAt)
        assertEquals(1, savedToken.version)
    }

    private fun createRefreshToken(): UUID {
        val suffix = UUID.randomUUID().toString().take(8)
        val tokenId = UUID.randomUUID()

        concurrency.inNewTransaction {
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
}
