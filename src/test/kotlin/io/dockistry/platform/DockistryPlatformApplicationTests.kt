package io.dockistry.platform

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Import(TestcontainersConfig::class)
@Testcontainers
class DockistryPlatformApplicationTests {

	@Test
	fun contextLoads() {
	}

}
