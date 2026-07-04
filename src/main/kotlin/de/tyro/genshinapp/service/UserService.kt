package de.tyro.genshinapp.service

import de.tyro.genshinapp.entity.User
import de.tyro.genshinapp.repository.UserRepository
import de.tyro.genshinapp.security.AppUserPrincipal
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
) : UserDetailsService {

    fun register(name: String, email: String, password: String): User {
        val normalizedName = name.trim()
        val normalizedEmail = email.trim().lowercase()

        if (normalizedName.length !in 2..60) {
            throw RegistrationException("registration.error.nameLength")
        }
        if (normalizedEmail.length > 254 || !EMAIL_PATTERN.matches(normalizedEmail)) {
            throw RegistrationException("registration.error.email")
        }
        if (password.length < 8 || password.toByteArray(Charsets.UTF_8).size > 72) {
            throw RegistrationException("registration.error.passwordLength")
        }
        if (userRepository.findByEmailIgnoreCase(normalizedEmail) != null) {
            throw RegistrationException("registration.error.duplicateEmail")
        }

        val user = User().also {
            it.name = normalizedName
            it.email = normalizedEmail
            it.passwordHash = passwordEncoder.encode(password)
        }
        return try {
            userRepository.save(user)
        } catch (_: DataIntegrityViolationException) {
            // Also covers two concurrent registrations for the same unique e-mail address.
            throw RegistrationException("registration.error.duplicateEmail")
        }
    }

    override fun loadUserByUsername(username: String): UserDetails {
        val user = userRepository.findByEmailIgnoreCase(username.trim())
            ?: throw UsernameNotFoundException("Account not found")
        val userId = user.id ?: throw UsernameNotFoundException("Account is incomplete")
        return AppUserPrincipal(
            id = userId,
            displayName = user.name,
            email = user.email,
            passwordHash = user.passwordHash,
        )
    }

    companion object {
        private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}

class RegistrationException(
    val messageKey: String,
) : IllegalArgumentException(messageKey)
