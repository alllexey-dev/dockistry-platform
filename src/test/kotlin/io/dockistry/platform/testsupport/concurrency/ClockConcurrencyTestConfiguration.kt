package io.dockistry.platform.testsupport.concurrency

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@TestConfiguration(proxyBeanMethods = false)
@Import(RepositoryConcurrencyTestConfiguration::class)
class ClockConcurrencyTestConfiguration {
    @Bean
    @Primary
    fun coordinatedClock(concurrency: ConcurrencyTestSupport): Clock =
        CoordinatedClock(concurrency, Instant.parse("2026-01-01T00:00:00Z"))
}

/** Only workers in a running scenario stop here; fixtures and background jobs do not. */
private class CoordinatedClock(
    private val concurrency: ConcurrencyTestSupport,
    private val current: Instant,
    private val zone: ZoneId = ZoneOffset.UTC
) : Clock() {
    override fun instant(): Instant {
        concurrency.checkpoint(ConcurrencyPoint.CLOCK_INSTANT)
        return current
    }

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = CoordinatedClock(concurrency, current, zone)
}
