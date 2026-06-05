package io.dockistry.platform.shared.api

import io.dockistry.platform.iam.application.CurrentUserProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class PingController(
    private val currentUserProvider: CurrentUserProvider
) {

    // helpful endpoints for testing
    @GetMapping("/api/public/ping")
    fun helloPublic() = "pong"

    @GetMapping("/api/private/ping")
    fun helloPrivate() = "pong to ${currentUserProvider.getUser().username}"
}