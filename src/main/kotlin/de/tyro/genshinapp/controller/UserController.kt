package de.tyro.genshinapp.controller

import de.tyro.genshinapp.configuration.GenshinRuntimeProperties
import de.tyro.genshinapp.configuration.LocalizedMessages
import de.tyro.genshinapp.security.AppUserPrincipal
import de.tyro.genshinapp.service.RegistrationException
import de.tyro.genshinapp.service.UserService
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class UserController(
    private val userService: UserService,
    private val messages: LocalizedMessages,
) {
    @GetMapping("/login")
    fun login(authentication: Authentication?): String =
        if (authentication.isLoggedIn()) "redirect:/" else "login"

    @GetMapping("/registration")
    fun registration(authentication: Authentication?): String =
        if (authentication.isLoggedIn()) "redirect:/" else "registration"

    @PostMapping("/registration")
    fun register(
        @RequestParam name: String,
        @RequestParam email: String,
        @RequestParam password: String,
        @RequestParam passwordConfirmation: String,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (password != passwordConfirmation) {
            model.addAttribute("errorMessage", messages.get("registration.error.passwordMismatch"))
            model.addAttribute("name", name)
            model.addAttribute("email", email)
            return "registration"
        }

        return try {
            userService.register(name, email, password)
            redirectAttributes.addFlashAttribute(
                "successMessage",
                messages.get("registration.success"),
            )
            "redirect:/login"
        } catch (exception: RegistrationException) {
            model.addAttribute("errorMessage", messages.get(exception.messageKey))
            model.addAttribute("name", name)
            model.addAttribute("email", email)
            "registration"
        }
    }

    private fun Authentication?.isLoggedIn(): Boolean =
        this != null && isAuthenticated && this !is AnonymousAuthenticationToken
}

@ControllerAdvice
class CurrentUserControllerAdvice(
    private val runtimeProperties: GenshinRuntimeProperties,
) {
    @ModelAttribute("currentUser")
    fun currentUser(
        @AuthenticationPrincipal principal: AppUserPrincipal?,
    ): CurrentUserView? {
        principal ?: return null
        return CurrentUserView(
            name = principal.displayName,
            email = principal.username,
        )
    }

    @ModelAttribute("desktopMode")
    fun desktopMode(): Boolean = runtimeProperties.desktop
}

data class CurrentUserView(
    val name: String,
    val email: String,
)
