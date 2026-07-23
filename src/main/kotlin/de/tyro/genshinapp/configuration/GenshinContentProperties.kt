package de.tyro.genshinapp.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "genshin.content")
class GenshinContentProperties {
    var cacheDirectory: String =
        Path.of(System.getProperty("user.home"), ".genshinapp", "cache").toString()

    var characterApiUrl: String = "https://genshin-db-api.vercel.app/api/v5"

    var fandomImageBaseUrl: String =
        "https://static.wikia.nocookie.net/gensin-impact/images"

    var hoyolabWikiApiUrl: String =
        "https://sg-wiki-api.hoyolab.com/hoyowiki/wapi"

    var hoyolabWikiEnabled: Boolean = true

    var connectTimeout: Duration = Duration.ofSeconds(8)

    var requestTimeout: Duration = Duration.ofSeconds(20)
}
