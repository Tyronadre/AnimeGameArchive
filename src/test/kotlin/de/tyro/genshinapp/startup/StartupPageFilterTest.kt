package de.tyro.genshinapp.startup

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupPageFilterTest {
    private val filter = StartupPageFilter()

    @Test
    fun `browser entry is returned without entering security or dashboard preparation`() {
        val response = MockHttpServletResponse()
        filter.doFilter(browserRequest(), response, FilterChain { _, _ ->
            error("Dashboard preparation must not run before the loading screen is sent")
        })

        assertEquals(200, response.status)
        assertTrue(response.contentAsString.contains("Lumine running in place"))
        assertTrue(response.contentAsString.contains("/startup/lumine-sprint.png"))
        assertTrue(response.contentAsString.contains("/startup/loader.js"))
        assertFalse(response.contentAsString.contains("__ARCHIVE_CONTEXT_PATH__"))
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertTrue(response.getHeader("Vary")!!.contains(StartupPageFilter.READY_HEADER))
        assertEquals(response.contentAsByteArray.size, response.contentLength)
    }

    @Test
    fun `background document request still passes through authentication and the real dashboard`() {
        val request = browserRequest().apply { addHeader(StartupPageFilter.READY_HEADER, "true") }
        assertPassesThrough(request)
    }

    @Test
    fun `direct fallback and other requests retain normal routing`() {
        assertPassesThrough(browserRequest().apply { addParameter("direct", "1") })
        assertPassesThrough(browserRequest().apply { requestURI = "/characters" })
        assertPassesThrough(MockHttpServletRequest("GET", "/"))
        assertPassesThrough(browserRequest().apply { method = "POST" })
    }

    @Test
    fun `loading assets respect the servlet context path`() {
        val request = browserRequest().apply {
            contextPath = "/archive"
            requestURI = "/archive/"
        }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, FilterChain { _, _ -> error("Unexpected dashboard request") })
        assertTrue(response.contentAsString.contains("/archive/startup/lumine-sprint.png"))
        assertTrue(response.contentAsString.contains("/archive/startup/loader.js"))
    }

    @Test
    fun `head response contains headers without a document body`() {
        val response = MockHttpServletResponse()
        filter.doFilter(browserRequest().apply { method = "HEAD" }, response, FilterChain { _, _ ->
            error("HEAD should not prepare dashboard data")
        })
        assertTrue(response.contentLength > 0)
        assertEquals(0, response.contentAsByteArray.size)
    }

    private fun browserRequest() = MockHttpServletRequest("GET", "/").apply {
        addHeader("Accept", "text/html,application/xhtml+xml")
    }

    private fun assertPassesThrough(request: MockHttpServletRequest) {
        var entered = false
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, FilterChain { _, _ -> entered = true })
        assertTrue(entered, "Normal security/controller chain should handle ${request.method} ${request.requestURI}")
        assertEquals(0, response.contentAsByteArray.size)
    }
}
