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

    var fandomImageBaseUrl: String =
        "https://static.wikia.nocookie.net/gensin-impact/images"

    var hoyolabWikiApiUrl: String =
        "https://sg-wiki-api.hoyolab.com/hoyowiki/wapi"

    var hoyolabWikiEnabled: Boolean = true

    var hoyolabCatalogSyncEnabled: Boolean = true

    /** Catch same-count edits even when the lightweight startup probes are unchanged. */
    var hoyolabCatalogFullRefreshInterval: Duration = Duration.ofDays(7)

    /** Reapply character page imports once during this startup, for development. */
    var rerunCharacterImports: Boolean = false

    var materialIdentityCatalogUrl: String =
        "https://raw.githubusercontent.com/Tyronadre/AnimeGameArchive/master/" +
            "src/main/resources/data/material-identities.json"

    var connectTimeout: Duration = Duration.ofSeconds(8)

    var requestTimeout: Duration = Duration.ofSeconds(20)
}
