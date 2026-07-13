package io.dockistry.platform.testsupport

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(
    private var current: Instant,
    private var zone: ZoneId = ZoneOffset.UTC
) : Clock() {

    override fun instant(): Instant = current
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

    fun set(instant: Instant) {
        current = instant
    }

    fun advance(duration: Duration) {
        current += duration
    }
}
