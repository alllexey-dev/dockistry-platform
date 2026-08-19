package io.dockistry.platform

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertFalse

@SpringBootTest
@Import(TestcontainersConfig::class)
@Testcontainers
class DockistryPlatformApplicationTests {

	@Autowired
	private lateinit var dataSource: HikariDataSource

	@Autowired
	@Qualifier("executionGateDataSource")
	private lateinit var executionGateDataSource: HikariDataSource

	@Test
	fun contextLoads() {
		assertFalse { dataSource.poolName == executionGateDataSource.poolName }
	}

}
