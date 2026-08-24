package de.tyro.genshinapp.service.importer

import de.tyro.genshinapp.service.catalog.ArtifactCatalogService
import de.tyro.genshinapp.service.catalog.CharacterCatalogService
import de.tyro.genshinapp.service.catalog.MaterialCatalogService
import de.tyro.genshinapp.service.catalog.WeaponCatalogService
import de.tyro.genshinapp.service.catalog.WeaponDataService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
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

data class BundledCatalogSnapshot(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val generatedAt: String = "",
    val normalizedCharacters: List<JsonNode> = emptyList(),
    val normalizedTalents: List<JsonNode> = emptyList(),
    val normalizedWeapons: List<JsonNode> = emptyList(),
    val hoyolabCharacters: List<HoyolabWikiEntry> = emptyList(),
    val hoyolabWeapons: List<HoyolabWikiEntry> = emptyList(),
    val hoyolabArtifacts: List<HoyolabWikiEntry> = emptyList(),
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 1
    }
}

/** Imports the build-time snapshot once, before the live startup synchronizers run. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
class BundledCatalogSnapshotImporter(
    private val staticDataCatalog: GenshinStaticDataCatalog,
    private val characterCatalogService: CharacterCatalogService,
    private val materialCatalogService: MaterialCatalogService,
    private val weaponDataService: WeaponDataService,
    private val weaponCatalogService: WeaponCatalogService,
    private val artifactCatalogService: ArtifactCatalogService,
    private val hoyolabEntryMapper: HoyolabCatalogEntryMapper,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    override fun run(args: ApplicationArguments) {
        val resource = ClassPathResource(SNAPSHOT_RESOURCE)
        if (!resource.exists()) {
            logger.debug("No bundled catalog snapshot is present")
            return
        }
        if (staticDataCatalog.readFolder(IMPORT_MARKER_FOLDER).isNotEmpty()) {
            logger.debug("Bundled catalog snapshot was already imported")
            return
        }

        val snapshot = resource.inputStream.use { input ->
            objectMapper.readValue(input, BundledCatalogSnapshot::class.java)
        }
        validate(snapshot)

        staticDataCatalog.synchronize(CHARACTERS_FOLDER, snapshot.normalizedCharacters)
        staticDataCatalog.synchronize(TALENTS_FOLDER, snapshot.normalizedTalents)
        staticDataCatalog.synchronize(WEAPONS_FOLDER, snapshot.normalizedWeapons)

        val characterChanges = characterCatalogService.importFromStaticData(
            snapshot.normalizedCharacters,
            snapshot.normalizedTalents,
        )
        val officialCharacterChanges = characterCatalogService.synchronizeHoyolabCharacterList(
            snapshot.hoyolabCharacters.mapNotNull(hoyolabEntryMapper::toCharacterSummary),
        )
        val weaponChanges = weaponDataService.importFromStaticData(snapshot.normalizedWeapons)
        val officialWeaponChanges = weaponCatalogService.synchronizeHoyolabWeaponList(
            snapshot.hoyolabWeapons.mapNotNull(hoyolabEntryMapper::toWeaponSummary),
        )
        val artifactChanges = artifactCatalogService.synchronizeHoyolabArtifactList(
            snapshot.hoyolabArtifacts,
        )
        materialCatalogService.synchronizeCharacters(characterCatalogService.getCharacters())

        staticDataCatalog.synchronize(
            IMPORT_MARKER_FOLDER,
            listOf(
                objectMapper.createObjectNode().also { marker ->
                    marker.put("name", "Bundled catalog snapshot")
                    marker.put("version", snapshot.formatVersion.toString())
                    marker.put("generatedAt", snapshot.generatedAt)
                },
            ),
        )
        logger.info(
            "Imported bundled catalog snapshot from {}: {} characters, {} weapons, " +
                "{} artifacts changed",
            snapshot.generatedAt,
            characterChanges + officialCharacterChanges,
            weaponChanges + officialWeaponChanges,
            artifactChanges,
        )
    }

    private fun validate(snapshot: BundledCatalogSnapshot) {
        require(snapshot.formatVersion == BundledCatalogSnapshot.CURRENT_FORMAT_VERSION) {
            "Unsupported bundled catalog snapshot format ${snapshot.formatVersion}"
        }
        require(snapshot.generatedAt.isNotBlank()) { "Bundled catalog snapshot has no timestamp" }
        require(snapshot.normalizedCharacters.isNotEmpty()) {
            "Bundled catalog snapshot has no normalized characters"
        }
        require(snapshot.normalizedTalents.isNotEmpty()) {
            "Bundled catalog snapshot has no normalized talents"
        }
        require(snapshot.normalizedWeapons.isNotEmpty()) {
            "Bundled catalog snapshot has no normalized weapons"
        }
        require(snapshot.hoyolabCharacters.isNotEmpty()) {
            "Bundled catalog snapshot has no HoYoWiki characters"
        }
        require(snapshot.hoyolabWeapons.isNotEmpty()) {
            "Bundled catalog snapshot has no HoYoWiki weapons"
        }
        require(snapshot.hoyolabArtifacts.isNotEmpty()) {
            "Bundled catalog snapshot has no HoYoWiki artifacts"
        }
    }

    private companion object {
        private const val SNAPSHOT_RESOURCE = "data/bundled-catalog-snapshot.json"
        private const val IMPORT_MARKER_FOLDER = "bundledsnapshot"
        private const val CHARACTERS_FOLDER = "characters"
        private const val TALENTS_FOLDER = "talents"
        private const val WEAPONS_FOLDER = "weapons"
    }
}

/** Build entry point. Fetches source JSON only; remote image bytes are never downloaded. */
fun main(args: Array<String>) {
    val output = outputPath(args)
    val objectMapper = jacksonObjectMapper()
    val properties = GenshinContentProperties()
    val hoyolab = HttpHoyolabWikiDataSource(objectMapper, properties)
    val normalized = GenshinDbApiClient(objectMapper, properties)
    val snapshot = BundledCatalogSnapshot(
        generatedAt = Instant.now().toString(),
        normalizedCharacters = normalized.fetchDataset(GenshinStaticDataset.CHARACTERS),
        normalizedTalents = normalized.fetchDataset(GenshinStaticDataset.TALENTS),
        normalizedWeapons = normalized.fetchDataset(GenshinStaticDataset.WEAPONS),
        hoyolabCharacters = hoyolab.listEntries(HoyolabWikiMenu.CHARACTERS),
        hoyolabWeapons = hoyolab.listEntries(HoyolabWikiMenu.WEAPONS),
        hoyolabArtifacts = hoyolab.listEntries(HoyolabWikiMenu.ARTIFACTS),
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

private fun outputPath(args: Array<String>): Path {
    val outputIndex = args.indexOf("--output")
    require(outputIndex >= 0 && outputIndex + 1 < args.size) {
        "Usage: --output <snapshot.json>"
    }
    return Path.of(args[outputIndex + 1]).toAbsolutePath().normalize()
}
