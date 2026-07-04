package de.tyro.genshinapp.configuration

import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.i18n.CookieLocaleResolver
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor
import java.time.Duration
import java.util.Locale

@Configuration
class LocaleConfiguration : WebMvcConfigurer {
    @Bean
    fun localeResolver(): LocaleResolver =
        CookieLocaleResolver("genshin-language").apply {
            setDefaultLocale(Locale.ENGLISH)
            setCookieMaxAge(Duration.ofDays(365))
        }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(
            LocaleChangeInterceptor().apply {
                paramName = "lang"
                isIgnoreInvalidLocale = true
            },
        )
    }
}

@Component
class LocalizedMessages(
    private val messageSource: MessageSource,
) {
    fun get(key: String, vararg arguments: Any?): String {
        val locale = LocaleContext.current()
        val resolvedArguments = arguments.map { argument ->
            if (argument is LocalizedMessageArgument) {
                messageSource.getMessage(argument.key, argument.arguments, locale)
            } else {
                argument
            }
        }.toTypedArray()
        return messageSource.getMessage(key, resolvedArguments, locale)
    }
}

data class LocalizedMessageArgument(
    val key: String,
    val arguments: Array<out Any?> = emptyArray(),
)

private object LocaleContext {
    fun current(): Locale =
        org.springframework.context.i18n.LocaleContextHolder.getLocale()
}
