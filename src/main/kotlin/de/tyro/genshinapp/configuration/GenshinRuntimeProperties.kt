package de.tyro.genshinapp.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "genshin.runtime")
class GenshinRuntimeProperties {
    var desktop: Boolean = false
    var openBrowser: Boolean = false
    var trayEnabled: Boolean = true
    var showWindow: Boolean = true
}
