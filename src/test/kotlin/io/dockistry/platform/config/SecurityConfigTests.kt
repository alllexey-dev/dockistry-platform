package io.dockistry.platform.config

import io.dockistry.platform.shared.api.ApiResponse
import jakarta.servlet.DispatcherType
import jakarta.servlet.RequestDispatcher
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `public endpoints allow unauthenticated get requests`() {
        mockMvc.get("/api/public/ping")
            .andExpect {
                status { isOk() }
            }
    }

    @Test
    fun `protected endpoints reject unauthenticated get requests`() {
        val result = mockMvc.get("/api/private/ping")
            .andExpect {
                status { isUnauthorized() }
                content { contentType(MediaType.APPLICATION_JSON) }
            }
            .andReturn()

        assertApiError(
            result = result,
            expectedCode = "authentication_required"
        )
    }

    @Test
    fun `error dispatches can return the original public not found response`() {
        mockMvc.get("/error") {
            with { request ->
                request.dispatcherType = DispatcherType.ERROR
                request
            }
            requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
            requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/public/missing")
        }.andExpect {
            status { isNotFound() }
        }
    }

    private fun assertApiError(
        result: MvcResult,
        expectedCode: String
    ) {
        val response = objectMapper.readValue<ApiResponse<Unit>>(result.response.contentAsString)

        assertNull(response.data)
        assertEquals(expectedCode, response.errorCode)

        val errorMessage = assertNotNull(response.errorMessage)
        assertTrue(errorMessage.isNotBlank())
    }
}
