package de.tyro.genshinapp.service.importer

import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.service.catalog.CharacterCatalogStore
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** Fetch remaining saved characters after the snapshot and live material catalogue have been applied. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class HoyolabCharacterImportRerunner(
    private val properties: GenshinContentProperties,
    private val characters: CharacterCatalogStore,
    private val pages: HoyolabCharacterPageService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (!properties.rerunCharacterImports || !properties.hoyolabWikiEnabled) return
        characters.getCharacters().forEach { character -> pages.enrichExact(character.key, force = true) }
    }
}
