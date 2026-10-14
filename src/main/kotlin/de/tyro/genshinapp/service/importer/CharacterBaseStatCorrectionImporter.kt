package de.tyro.genshinapp.service.importer

import de.tyro.genshinapp.service.catalog.CharacterBaseStatCorrections
import de.tyro.genshinapp.service.catalog.CharacterCatalogService
import de.tyro.genshinapp.service.catalog.CharacterCatalogStore
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** Backfills missing bases offline without requiring previously imported characters to be imported again. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 45)
class CharacterBaseStatCorrectionImporter(
    private val store: CharacterCatalogStore,
    private val characters: CharacterCatalogService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        store.getCharacters().forEach { character ->
            val original = character.stats ?: return@forEach
            val corrected = CharacterBaseStatCorrections.fillMissing(character.key, original)
            if (corrected != original) characters.saveHoyolabEnrichment(character.copy(stats = corrected))
        }
    }
}
