package io.dockistry.platform

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DockistryPlatformApplication

fun main(args: Array<String>) {
	runApplication<DockistryPlatformApplication>(*args)
}
