package io.dockistry.platform.setup.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.infrastructure.UserRepository
import io.dockistry.platform.setup.domain.InitialAdminAlreadyExistsException
import io.dockistry.platform.shared.domain.SystemSettingKeys
import io.dockistry.platform.shared.infrastructure.SystemSettingRepository
import io.dockistry.platform.testsupport.concurrency.ConcurrencyTestConfiguration
import io.dockistry.platform.testsupport.concurrency.ConcurrencyTestSupport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.assertInstanceOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfig::class, ConcurrencyTestConfiguration::class)
@Testcontainers
class InitialAdminServiceConcurrencyTests {

    companion object {
        const val REQUEST_COUNT = 5
    }

    @Autowired
    private lateinit var initialAdminService: InitialAdminService

    @Autowired
    private lateinit var systemSettingRepository: SystemSettingRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var concurrency: ConcurrencyTestSupport

    @Test
    fun `only one request succeeds out of several requests for initial admin bootstrap`() {
        val outcomes = concurrency.run(workers = REQUEST_COUNT) {
            startTogether(*Array(REQUEST_COUNT) {
                {
                    val suffix = UUID.randomUUID().toString().take(8)
                    initialAdminService.initializeAdmin(
                        username = "concurrent-admin-$suffix",
                        email = "concurrent-admin-$suffix@dockistry.io",
                        password = "password-123"
                    )
                }
            })
        }

        val failures = outcomes.mapNotNull { it.exceptionOrNull() }
        failures.forEach { assertInstanceOf<InitialAdminAlreadyExistsException>(it) }
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(REQUEST_COUNT - 1, failures.size)
    }

    @AfterEach
    fun tearDown() {
        systemSettingRepository.findById(SystemSettingKeys.INITIAL_ADMIN_BOOTSTRAP_EXECUTED)
            .ifPresent {
                systemSettingRepository.delete(it)
            }

        userRepository.findAll()
            .filter { it.username.startsWith("concurrent-admin-") }
            .forEach { userRepository.delete(it) }
    }
}
