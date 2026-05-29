package io.dockistry.platform

import io.dockistry.platform.testsupport.MutableClock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Instant

@TestConfiguration
class TestClockConfig {

    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock(
        Instant.parse("2026-01-01T00:00:00Z")
    )

}
