package de.tyro.genshinapp.service.importer

import de.tyro.genshinapp.service.catalog.ArtifactCatalogService
import de.tyro.genshinapp.service.catalog.CharacterCatalogService
import de.tyro.genshinapp.service.catalog.MaterialCatalogService
import de.tyro.genshinapp.service.catalog.WeaponCatalogService
import de.tyro.genshinapp.service.catalog.HoyowikiCatalogStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.startup.ApplicationStartupStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class BundledCatalogSnapshot(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val generatedAt: String = "",
    val hoyolabCharacters: List<HoyolabWikiEntry> = emptyList(),
    val hoyolabWeapons: List<HoyolabWikiEntry> = emptyList(),
    val hoyolabArtifacts: List<HoyolabWikiEntry> = emptyList(),
    val hoyolabMaterials: List<HoyolabWikiEntry> = emptyList(),
    val hoyolabCharacterPages: List<JsonNode> = emptyList(),
    val hoyolabWeaponPages: List<JsonNode> = emptyList(),
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 2
    }
}

/** Idempotently applies the build-time snapshot before live startup synchronization. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
class BundledCatalogSnapshotImporter(
    private val characterCatalogService: CharacterCatalogService,
    private val materialCatalogService: MaterialCatalogService,
    private val weaponCatalogService: WeaponCatalogService,
    private val artifactCatalogService: ArtifactCatalogService,
    private val hoyolabEntryMapper: HoyolabCatalogEntryMapper,
    private val characterPageService: HoyolabCharacterPageService,
    private val weaponPageService: HoyolabWeaponGalleryService,
    private val properties: GenshinContentProperties,
    private val hoyowikiCatalogStore: HoyowikiCatalogStore,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    override fun run(args: ApplicationArguments) {
        ApplicationStartupStatus.report(
            "Reading bundled game catalogue…",
            "Loading the packaged HoYoWiki snapshot",
        )
        val resource = ClassPathResource(SNAPSHOT_RESOURCE)
        if (!resource.exists()) {
            logger.debug("No bundled catalog snapshot is present")
            ApplicationStartupStatus.report(
                "Preparing live catalogue refresh…",
                "No bundled snapshot was found",
            )
            return
        }
        val snapshot = resource.inputStream.use { input ->
            objectMapper.readValue(input, BundledCatalogSnapshot::class.java)
        }
        validate(snapshot)

        if (hoyowikiCatalogStore.hasBundledVersion(snapshot.generatedAt) && !properties.rerunCharacterImports) {
            logger.info("Bundled catalogue {} is already imported", snapshot.generatedAt)
            artifactCatalogService.refreshFromDatabase()
            return
        }

        ApplicationStartupStatus.report(
            "Importing bundled materials…",
            "${snapshot.hoyolabMaterials.size} catalogue entries",
        )
        val materialChanges = materialCatalogService.synchronizeHoyolabMaterialList(
            snapshot.hoyolabMaterials.mapNotNull(hoyolabEntryMapper::toMaterialSummary),
        )
        ApplicationStartupStatus.report(
            "Importing bundled characters…",
            "${snapshot.hoyolabCharacters.size} catalogue entries",
        )
        val officialCharacterChanges = characterCatalogService.synchronizeHoyolabCharacterList(
            snapshot.hoyolabCharacters.mapNotNull(hoyolabEntryMapper::toCharacterSummary),
        )
        ApplicationStartupStatus.report(
            "Importing bundled weapons…",
            "${snapshot.hoyolabWeapons.size} catalogue entries",
        )
        val officialWeaponChanges = weaponCatalogService.synchronizeHoyolabWeaponList(
            snapshot.hoyolabWeapons.mapNotNull(hoyolabEntryMapper::toWeaponSummary),
        )
        ApplicationStartupStatus.report(
            "Importing bundled artifacts…",
            "${snapshot.hoyolabArtifacts.size} catalogue entries",
        )
        val artifactChanges = artifactCatalogService.synchronizeHoyolabArtifactList(
            snapshot.hoyolabArtifacts,
        )
        val characterPageChanges = importPagesWithProgress(
            "Importing character details…",
            snapshot.hoyolabCharacterPages,
            { page -> characterPageService.importPage(page, force = properties.rerunCharacterImports) },
        )
        val weaponPageChanges = importPagesWithProgress(
            "Importing weapon details…",
            snapshot.hoyolabWeaponPages,
            weaponPageService::importPage,
        )
        ApplicationStartupStatus.report(
            "Indexing progression materials…",
            "Connecting character requirements to the material catalogue",
        )
        materialCatalogService.synchronizeCharacters(characterCatalogService.getCharacters())
        val generatedAt = Instant.parse(snapshot.generatedAt)
        listOf(
            HoyolabWikiMenu.MATERIALS to snapshot.hoyolabMaterials,
            HoyolabWikiMenu.CHARACTERS to snapshot.hoyolabCharacters,
            HoyolabWikiMenu.WEAPONS to snapshot.hoyolabWeapons,
            HoyolabWikiMenu.ARTIFACTS to snapshot.hoyolabArtifacts,
        ).forEach { (menu, entries) ->
            hoyowikiCatalogStore.recordCatalog(menu, entries, generatedAt, live = false)
        }
        hoyowikiCatalogStore.markBundledVersion(snapshot.generatedAt)
        logger.info(
            "Imported bundled catalog snapshot from {}: {} characters, {} weapons, " +
                "{} artifacts changed",
            snapshot.generatedAt,
            officialCharacterChanges + characterPageChanges,
            officialWeaponChanges + weaponPageChanges,
            artifactChanges + materialChanges,
        )
        ApplicationStartupStatus.report(
            "Preparing live catalogue refresh…",
            "Bundled game data is ready",
        )
    }

    private fun importPagesWithProgress(
        task: String,
        pages: List<JsonNode>,
        importer: (JsonNode) -> Any?,
    ): Int {
        if (pages.isEmpty()) return 0
        ApplicationStartupStatus.report(task, "0 of ${pages.size}")
        var changed = 0
        pages.forEachIndexed { index, page ->
            if (importer(page) != null) changed++
            val completed = index + 1
            if (completed == pages.size || completed % PROGRESS_REPORT_INTERVAL == 0) {
                ApplicationStartupStatus.report(task, "$completed of ${pages.size}")
            }
        }
        return changed
    }

    private fun validate(snapshot: BundledCatalogSnapshot) {
        require(snapshot.formatVersion == BundledCatalogSnapshot.CURRENT_FORMAT_VERSION) {
            "Unsupported bundled catalog snapshot format ${snapshot.formatVersion}"
        }
        require(snapshot.generatedAt.isNotBlank()) { "Bundled catalog snapshot has no timestamp" }
        require(snapshot.hoyolabCharacters.isNotEmpty()) {
            "Bundled catalog snapshot has no HoYoWiki characters"
        }
        require(snapshot.hoyolabWeapons.isNotEmpty()) {
            "Bundled catalog snapshot has no HoYoWiki weapons"
        }
        require(snapshot.hoyolabArtifacts.isNotEmpty()) {
            "Bundled catalog snapshot has no HoYoWiki artifacts"
        }
        require(snapshot.hoyolabMaterials.isNotEmpty()) {
            "Bundled catalog snapshot has no HoYoWiki materials"
        }
        require(snapshot.hoyolabCharacterPages.isNotEmpty()) {
            "Bundled catalog snapshot has no HoYoWiki character pages"
        }
        require(snapshot.hoyolabWeaponPages.isNotEmpty()) {
            "Bundled catalog snapshot has no HoYoWiki weapon pages"
        }
    }

    private companion object {
        private const val SNAPSHOT_RESOURCE = "data/bundled-catalog-snapshot.json"
        private const val PROGRESS_REPORT_INTERVAL = 10
    }
}

/** Build entry point. Fetches source JSON only; remote image bytes are never downloaded. */
fun main(args: Array<String>) {
    val output = outputPath(args)
    val objectMapper = jacksonObjectMapper()
    val properties = GenshinContentProperties()
    val hoyolab = HttpHoyolabWikiDataSource(objectMapper, properties)
    val characters = hoyolab.listEntries(HoyolabWikiMenu.CHARACTERS)
    val weapons = hoyolab.listEntries(HoyolabWikiMenu.WEAPONS)
    val snapshot = BundledCatalogSnapshot(
        generatedAt = Instant.now().toString(),
        hoyolabCharacters = characters,
        hoyolabWeapons = weapons,
        hoyolabArtifacts = hoyolab.listEntries(HoyolabWikiMenu.ARTIFACTS),
        hoyolabMaterials = hoyolab.listEntries(HoyolabWikiMenu.MATERIALS),
        hoyolabCharacterPages = fetchPages(characters, hoyolab),
        hoyolabWeaponPages = fetchPages(weapons, hoyolab),
    )
    Files.createDirectories(output.toAbsolutePath().normalize().parent)
    val temporary = Files.createTempFile(output.parent, "bundled-catalog-snapshot", ".tmp")
    try {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), snapshot)
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun fetchPages(
    entries: List<HoyolabWikiEntry>,
    source: HoyolabWikiDataSource,
): List<JsonNode> {
    val executor = Executors.newFixedThreadPool(8)
    return try {
        executor.invokeAll(entries.map { entry ->
            Callable {
                requireNotNull(source.fetchPage(entry.entryPageId)) {
                    "HoYoWiki page ${entry.entryPageId} (${entry.name}) is unavailable"
                }
            }
        }).map { it.get() }
    } finally {
        executor.shutdown()
    }
}

private fun outputPath(args: Array<String>): Path {
    val outputIndex = args.indexOf("--output")
    require(outputIndex >= 0 && outputIndex + 1 < args.size) {
        "Usage: --output <snapshot.json>"
    }
    return Path.of(args[outputIndex + 1]).toAbsolutePath().normalize()
}
