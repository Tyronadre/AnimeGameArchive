package de.tyro.genshinapp.configuration

import de.tyro.genshinapp.entity.User
import de.tyro.genshinapp.repository.UserRepository
import de.tyro.genshinapp.security.AppUserPrincipal
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Configuration
@Profile("desktop")
class DesktopSecurityConfiguration {
    @Bean
    fun desktopSecurityFilterChain(
        http: HttpSecurity,
        desktopAuthenticationFilter: DesktopAuthenticationFilter,
    ): SecurityFilterChain {
        http
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .csrf {
                it.ignoringRequestMatchers(
                    "/api/desktop/irminsul/**",
                    "/goals",
                )
            }
            .addFilterBefore(desktopAuthenticationFilter, AnonymousAuthenticationFilter::class.java)
            .logout { it.logoutSuccessUrl("/") }
        return http.build()
    }
}

@Component
@Profile("desktop")
class DesktopAuthenticationFilter(
    private val desktopUserProvider: DesktopUserProvider,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (SecurityContextHolder.getContext().authentication == null) {
            val principal = desktopUserProvider.principal()
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                    principal,
                    null,
                    principal.authorities,
                )
        }
        filterChain.doFilter(request, response)
    }
}

@Component
@Profile("desktop")
class DesktopUserProvider(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    @Volatile
    private var cachedPrincipal: AppUserPrincipal? = null

    fun principal(): AppUserPrincipal =
        cachedPrincipal ?: synchronized(this) {
            cachedPrincipal ?: loadOrCreatePrincipal().also {
                cachedPrincipal = it
            }
        }

    private fun loadOrCreatePrincipal(): AppUserPrincipal {
        val user = userRepository.findByEmailIgnoreCase(DESKTOP_EMAIL)
            ?: userRepository.save(
                User().also {
                    it.name = "Local Traveler"
                    it.email = DESKTOP_EMAIL
                    it.passwordHash = passwordEncoder.encode(UUID.randomUUID().toString())
                },
            )
        return AppUserPrincipal(
            id = requireNotNull(user.id),
            displayName = user.name,
            email = user.email,
            passwordHash = user.passwordHash,
        )
    }

    companion object {
        const val DESKTOP_EMAIL = "desktop@localhost.invalid"
    }
}
