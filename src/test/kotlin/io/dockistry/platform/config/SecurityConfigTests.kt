package io.dockistry.platform.config

import jakarta.servlet.DispatcherType
import jakarta.servlet.RequestDispatcher
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityConfigTests.TestController::class)
class SecurityConfigTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `public endpoints allow unauthenticated get requests`() {
        mockMvc.perform(get("/api/public/ping"))
            .andExpect(status().isOk)
    }

    @Test
    fun `protected endpoints reject unauthenticated get requests`() {
        mockMvc.perform(get("/api/private/ping"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `error dispatches can return the original public not found response`() {
        mockMvc.perform(
            get("/error")
                .with { request ->
                    request.dispatcherType = DispatcherType.ERROR
                    request
                }
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/public/missing")
        ).andExpect(status().isNotFound)
    }

    @RestController
    class TestController {
        @GetMapping("/api/public/ping")
        fun publicPing() = "pong"

        @GetMapping("/api/private/ping")
        fun privatePing() = "pong"
    }
}
