package de.tyro.genshinapp.service

import de.tyro.genshinapp.configuration.GenshinContentProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class GenshinStaticDataStartupImporter(
    private val properties: GenshinContentProperties,
    private val source: GenshinStaticDataSource,
    private val catalog: GenshinStaticDataCatalog,
    private val characterCatalogService: CharacterCatalogService,
    private val materialCatalogService: MaterialCatalogService,
    private val weaponDataService: WeaponDataService,
    private val artifactCatalogService: ArtifactCatalogService,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val started = AtomicBoolean()

    override fun run(args: ApplicationArguments) {
        if (!properties.staticImportEnabled) {
            logger.info("genshin-db static data import is disabled")
            return
        }
        if (!started.compareAndSet(false, true)) return

        val folders = properties.staticImportFolders.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(String::lowercase)
            .distinct()
            .toList()
        val failures = linkedMapOf<String, Throwable>()
        val executor = Executors.newFixedThreadPool(folders.size.coerceIn(1, MAX_PARALLEL_FETCHES))
        try {
            val fetches = folders.associateWith { folder ->
                CompletableFuture.supplyAsync({ source.fetchFolder(folder) }, executor)
            }
            folders.forEach { folder ->
                runCatching {
                    val result = catalog.synchronize(folder, fetches.getValue(folder).join())
                    logger.info(
                        "Compared genshin-db folder '{}': {} source, {} created, {} updated, " +
                            "{} unchanged, {} removed",
                        result.folder,
                        result.sourceCount,
                        result.createdCount,
                        result.updatedCount,
                        result.unchangedCount,
                        result.removedCount,
                    )
                }.onFailure { wrappedError ->
                    val error = (wrappedError as? CompletionException)?.cause ?: wrappedError
                    failures[folder] = error
                    logger.warn(
                        "Could not refresh genshin-db folder '{}'; keeping stored data: {}",
                        folder,
                        error.message,
                    )
                    logger.debug("genshin-db import failure for '$folder'", error)
                }
            }
        } finally {
            executor.shutdownNow()
        }

        refreshApplicationCatalogs()
        if (failures.isNotEmpty() && properties.staticImportFailOnError) {
            throw IllegalStateException(
                "genshin-db static data import failed for ${failures.keys.joinToString()}",
                failures.values.first(),
            )
        }
    }

    private fun refreshApplicationCatalogs() {
        val characters = catalog.readFolder(CHARACTERS_FOLDER)
        if (characters.isNotEmpty()) {
            val changed = characterCatalogService.importFromStaticData(
                characters,
                catalog.readFolder(TALENTS_FOLDER),
            )
            materialCatalogService.synchronizeCharacters(characterCatalogService.getCharacters())
            logger.info("Refreshed {} character catalog entries from genshin-db", changed)
        }

        val weapons = catalog.readFolder(WEAPONS_FOLDER)
        if (weapons.isNotEmpty()) {
            val changed = weaponDataService.importFromStaticData(weapons)
            logger.info("Refreshed {} weapon catalog entries from genshin-db", changed)
        }

        artifactCatalogService.refreshFromDatabase()
    }

    companion object {
        private const val CHARACTERS_FOLDER = "characters"
        private const val TALENTS_FOLDER = "talents"
        private const val WEAPONS_FOLDER = "weapons"
        private const val MAX_PARALLEL_FETCHES = 4
    }
}
