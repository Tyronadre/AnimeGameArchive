package de.tyro.genshinapp.service

import de.tyro.genshinapp.entity.User
import de.tyro.genshinapp.repository.UserRepository
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UserServiceTest {
    private val repository = mock(UserRepository::class.java)
    private val passwordEncoder = BCryptPasswordEncoder()
    private val service = UserService(repository, passwordEncoder)

    @Test
    fun `registers a normalized account with a hashed password`() {
        `when`(repository.findByEmailIgnoreCase("player@example.com")).thenReturn(null)
        `when`(repository.save(any(User::class.java))).thenAnswer { invocation ->
            invocation.getArgument<User>(0).also { it.id = 42 }
        }

        val user = service.register(
            name = "  Traveler  ",
            email = " Player@Example.com ",
            password = "correct horse battery staple",
        )

        assertEquals("Traveler", user.name)
        assertEquals("player@example.com", user.email)
        assertNotEquals("correct horse battery staple", user.passwordHash)
        assertTrue(passwordEncoder.matches("correct horse battery staple", user.passwordHash))
        verify(repository).save(user)
    }

    @Test
    fun `rejects a duplicate email address`() {
        `when`(repository.findByEmailIgnoreCase("player@example.com")).thenReturn(User())

        assertThrows<RegistrationException> {
            service.register("Traveler", "player@example.com", "long-enough-password")
        }
    }

    @Test
    fun `loads an account as a Spring Security principal`() {
        val user = User().also {
            it.id = 42
            it.name = "Traveler"
            it.email = "player@example.com"
            it.passwordHash = passwordEncoder.encode("long-enough-password")
        }
        `when`(repository.findByEmailIgnoreCase("player@example.com")).thenReturn(user)

        val principal = service.loadUserByUsername("player@example.com")

        assertEquals("player@example.com", principal.username)
        assertTrue(passwordEncoder.matches("long-enough-password", principal.password))
        assertTrue(principal.authorities.any { it.authority == "ROLE_USER" })
    }
}
