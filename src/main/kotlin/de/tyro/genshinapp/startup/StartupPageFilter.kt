package de.tyro.genshinapp.startup

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Return a small first document before authentication and dashboard data preparation. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class StartupPageFilter : OncePerRequestFilter() {
    private val page = ClassPathResource("startup/loading.html").inputStream.use {
        it.readBytes().toString(Charsets.UTF_8)
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method !in setOf("GET", "HEAD") ||
            request.requestURI.removePrefix(request.contextPath) != "/" ||
            request.getHeader("Accept")?.contains("text/html", ignoreCase = true) != true ||
            request.getHeader(READY_HEADER) == "true" ||
            request.getParameter("direct") == "1"

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val body = page.replace("__ARCHIVE_CONTEXT_PATH__", request.contextPath)
            .toByteArray(Charsets.UTF_8)
        response.contentType = "text/html;charset=UTF-8"
        // The next document contains user data and CSRF tokens; never cache the hand-off.
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Vary", "Accept, $READY_HEADER")
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setContentLength(body.size)
        if (request.method != "HEAD") response.outputStream.write(body)
    }

    companion object {
        const val READY_HEADER = "X-Archive-Ready"
    }
}
