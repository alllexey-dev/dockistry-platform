package io.dockistry.platform.setup.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.infrastructure.UserRepository
import io.dockistry.platform.setup.domain.InitialAdminAlreadyExistsException
import io.dockistry.platform.shared.domain.SystemSettingKeys
import io.dockistry.platform.shared.infrastructure.SystemSettingRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.assertInstanceOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfig::class)
@Testcontainers
class InitialAdminServiceConcurrencyTests {

    companion object {
        const val N_THREADS = 5
    }

    @Autowired
    private lateinit var initialAdminService: InitialAdminService

    @Autowired
    private lateinit var systemSettingRepository: SystemSettingRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Test
    fun `only one request succeeds out of several requests for initial admin bootstrap`() {
        val executorService = Executors.newFixedThreadPool(N_THREADS)
        val barrier = CyclicBarrier(N_THREADS)
        val successCounter = AtomicInteger()
        val exceptionList = CopyOnWriteArrayList<Throwable>()

        repeat(N_THREADS) {
            val suffix = UUID.randomUUID().toString().take(4)

            executorService.submit {
                try {
                    barrier.await()

                    initialAdminService.initializeAdmin(
                        username = "concurrent-initial-admin-$suffix",
                        email = "concurrent-initial-admin-$suffix@dockistry.io",
                        password = "password-123"
                    )

                    successCounter.incrementAndGet()
                } catch (e: Exception) {
                    exceptionList.add(e)
                }
            }
        }

        executorService.shutdown()
        val finished = executorService.awaitTermination(10, TimeUnit.SECONDS)
        assertTrue(finished, "Threads haven't finished execution in given timeout")

        exceptionList.forEach {
            assertInstanceOf<InitialAdminAlreadyExistsException>(it)
        }

        assertEquals(1, successCounter.get())
        assertEquals(N_THREADS - 1, exceptionList.size)
    }

    @AfterEach
    fun tearDown() {
        systemSettingRepository.findById(SystemSettingKeys.INITIAL_ADMIN_BOOTSTRAP_EXECUTED)
            .ifPresent {
                systemSettingRepository.delete(it)
            }

        userRepository.findAll()
            .filter { it.username.startsWith("concurrent-initial-admin") }
            .forEach { userRepository.delete(it) }
    }
}
