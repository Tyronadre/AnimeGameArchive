package de.tyro.genshinapp.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.service.ArtifactOptimizationProfile
import de.tyro.genshinapp.service.ArtifactOptimizerBuildCatalog
import de.tyro.genshinapp.service.ArtifactOptimizerBuildProfile
import de.tyro.genshinapp.service.ArtifactOptimizerBuildSetRecommendation
import de.tyro.genshinapp.service.ArtifactOptimizerBuildStatRecommendation
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

private const val DEFAULT_SOURCE_URL =
    "https://game8.co/games/Genshin-Impact/archives/530535"
private const val DEFAULT_OUTPUT =
    "src/main/resources/data/artifact-optimizer-builds.json"
private const val DEFAULT_CHARACTER_DATA =
    "src/main/resources/data/characters"

fun main(args: Array<String>) {
    val options = ScraperOptions.parse(args.toList())
    val html = options.input?.let(Files::readString)
        ?: fetchHtml(options.sourceUrl)
    val characterIndex = CharacterIndex.load(options.characterDataDirectory)
    val catalog = Game8BuildProfileParser.parse(
        html = html,
        sourceUrl = options.sourceUrl,
        scrapedAt = Instant.now().toString(),
        characterIndex = characterIndex,
    )

    options.output.parent?.let(Files::createDirectories)
    jacksonObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(options.output.toFile(), catalog)
    println(
        "Wrote ${catalog.profiles.size} Game8 artifact optimizer profiles to " +
            options.output.toAbsolutePath(),
    )
}

object Game8BuildProfileParser {
    fun parse(
        html: String,
        sourceUrl: String = DEFAULT_SOURCE_URL,
        scrapedAt: String? = null,
        characterIndex: CharacterIndex = CharacterIndex.empty(),
    ): ArtifactOptimizerBuildCatalog {
        val document = Jsoup.parse(html, sourceUrl)
        val table = document.select("table").firstOrNull {
            it.selectFirst("th[data-cell=character]") != null &&
                it.selectFirst("th[data-cell=build]") != null
        } ?: return ArtifactOptimizerBuildCatalog(
            sourceName = "Game8",
            sourceUrl = sourceUrl,
            scrapedAt = scrapedAt,
        )

        val profiles = table.select("tbody > tr").flatMap { row ->
            val cells = row.select("> td")
            val characterCell = cells.firstOrNull() ?: return@flatMap emptyList()
            val characterName = cleanText(
                characterCell.selectFirst("a")?.ownText()
                    ?.takeIf(String::isNotBlank)
                    ?: characterCell.text(),
            )
            if (characterName.isBlank()) return@flatMap emptyList()

            val metadata = characterIndex.find(characterName)
            val characterKey = metadata?.key ?: GoodKeyNormalizer.normalize(characterName)
            val characterUrl = characterCell.selectFirst("a[href]")?.absUrl("href")
                ?.takeIf(String::isNotBlank)
            val buildCells = cells.drop(1).filter { it.selectFirst("b.a-bold") != null }

            buildCells.mapIndexedNotNull { index, buildCell ->
                parseBuildCell(
                    cell = buildCell,
                    characterKey = characterKey,
                    characterName = metadata?.name ?: characterName,
                    characterElement = metadata?.element,
                    characterUrl = characterUrl,
                    buildIndex = index + 1,
                )
            }
        }

        return ArtifactOptimizerBuildCatalog(
            sourceName = "Game8",
            sourceUrl = sourceUrl,
            sourceLastUpdated = sourceLastUpdated(document.text()),
            scrapedAt = scrapedAt,
            profiles = profiles,
        )
    }

    private fun parseBuildCell(
        cell: Element,
        characterKey: String,
        characterName: String,
        characterElement: String?,
        characterUrl: String?,
        buildIndex: Int,
    ): ArtifactOptimizerBuildProfile? {
        val buildName = cleanText(cell.selectFirst("b.a-bold")?.text().orEmpty())
        if (buildName.isBlank()) return null

        val equipmentHtml = cell.html().substringBeforeMainStats()
        val equipment = Jsoup.parseBodyFragment(equipmentHtml, cell.baseUri())
            .select("div.align")
        val artifactSets = equipment.mapNotNull { parseArtifactSet(it.text()) }
        val weaponName = equipment.firstOrNull { div ->
            div.selectFirst("a") != null && parseArtifactSet(div.text()) == null
        }?.text()?.let(::cleanText)?.takeIf(String::isNotBlank)

        val mainStats = cell.select("div.align").mapNotNull { div ->
            val slot = slotFromImage(div.selectFirst("img")?.attr("alt").orEmpty())
                ?: return@mapNotNull null
            val raw = cleanText(div.text().substringAfter(':', ""))
            if (raw.isBlank()) {
                null
            } else {
                slot to statRecommendation(raw, characterElement)
            }
        }.toMap()
        val substats = substatTexts(cell).map {
            statRecommendation(it, characterElement)
        }.filter { it.keys.isNotEmpty() }
        val profileKey = inferProfileKey(
            buildName = buildName,
            mainStatKeys = mainStats.values.flatMap { it.keys },
            substatKeys = substats.flatMap { it.keys },
        )

        return ArtifactOptimizerBuildProfile(
            id = "${characterKey}-${slug(buildName)}-$buildIndex",
            characterKey = characterKey,
            characterName = characterName,
            characterUrl = characterUrl,
            buildName = buildName,
            profileKey = profileKey,
            weaponName = weaponName,
            artifactSets = artifactSets,
            mainStats = mainStats,
            substats = substats,
        )
    }

    private fun parseArtifactSet(rawText: String): ArtifactOptimizerBuildSetRecommendation? {
        val text = cleanText(rawText)
        val match = Regex("""^(.+?)\s*\((2|4)-pc\)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?: return null
        val name = cleanText(match.groupValues[1])
        return ArtifactOptimizerBuildSetRecommendation(
            name = name,
            key = GoodKeyNormalizer.normalize(name),
            count = match.groupValues[2].toInt(),
        )
    }

    private fun slotFromImage(alt: String): String? {
        val normalized = alt.lowercase()
        return when {
            "hourglass" in normalized -> "sands"
            "goblet" in normalized -> "goblet"
            "circlet" in normalized -> "circlet"
            else -> null
        }
    }

    private fun substatTexts(cell: Element): List<String> {
        val html = cell.html()
        val marker = Regex(
            """<b[^>]*>\s*Sub Stats:?\s*</b>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html) ?: return emptyList()
        val fragment = Jsoup.parseBodyFragment(html.substring(marker.range.last + 1))
        return fragment.wholeText()
            .replace('\u00a0', ' ')
            .replace("•", "\n•")
            .lines()
            .flatMap { it.split('•') }
            .map(::cleanText)
            .filter(String::isNotBlank)
    }

    private fun statRecommendation(
        raw: String,
        characterElement: String?,
    ): ArtifactOptimizerBuildStatRecommendation =
        ArtifactOptimizerBuildStatRecommendation(
            raw = raw,
            keys = statKeys(raw, characterElement),
        )

    private fun statKeys(raw: String, characterElement: String?): List<String> {
        val normalizedRaw = raw
            .replace("Crit", "CRIT", ignoreCase = true)
            .replace("CRIT Rate / DMG", "CRIT Rate or CRIT DMG")
            .replace("CRIT Rate/DMG", "CRIT Rate or CRIT DMG")
            .replace("/", " or ")
            .replace(",", " or ")
        val parts = normalizedRaw
            .split(Regex("""\s+or\s+|\s*&\s+|\s*;\s*""", RegexOption.IGNORE_CASE))
            .map(::cleanText)
            .filter(String::isNotBlank)
        return parts.flatMap { part -> statKeysForPart(part, characterElement) }
            .distinct()
    }

    private fun statKeysForPart(part: String, characterElement: String?): List<String> {
        val normalized = part.lowercase()
        val keys = mutableListOf<String>()
        fun add(key: String) {
            if (key !in keys) keys += key
        }

        when {
            "crit rate" in normalized -> add("critRate_")
            "crit dmg" in normalized || "crit damage" in normalized -> add("critDMG_")
            normalized == "crit" || normalized == "crit stat" -> {
                add("critRate_")
                add("critDMG_")
            }
            "energy recharge" in normalized -> add("enerRech_")
            "elemental mastery" in normalized -> add("eleMas")
            "healing bonus" in normalized -> add("heal_")
            "element dmg bonus" in normalized ->
                characterElement?.let { add("${it.lowercase()}_dmg_") }
        }

        ELEMENT_DAMAGE_KEYS.forEach { (label, key) ->
            if ("$label dmg bonus" in normalized || normalized == "$label dmg") add(key)
        }
        if (Regex("""\bhp\s*%""", RegexOption.IGNORE_CASE).containsMatchIn(part)) {
            add("hp_")
        } else if (Regex("""\bhp\b""", RegexOption.IGNORE_CASE).containsMatchIn(part)) {
            add("hp")
        }
        if (Regex("""\batk\s*%""", RegexOption.IGNORE_CASE).containsMatchIn(part)) {
            add("atk_")
        } else if (Regex("""\batk\b""", RegexOption.IGNORE_CASE).containsMatchIn(part)) {
            add("atk")
        }
        if (Regex("""\bdef\s*%""", RegexOption.IGNORE_CASE).containsMatchIn(part)) {
            add("def_")
        } else if (Regex("""\bdef\b""", RegexOption.IGNORE_CASE).containsMatchIn(part)) {
            add("def")
        }
        return keys
    }

    private fun inferProfileKey(
        buildName: String,
        mainStatKeys: List<String>,
        substatKeys: List<String>,
    ): String {
        val build = buildName.lowercase()
        val allKeys = mainStatKeys + substatKeys
        return when {
            listOf("hyperbloom", "burgeon", "bloom", "swirl", "reaction").any { it in build } ||
                mainStatKeys.count { it == "eleMas" } >= 2 ->
                ArtifactOptimizationProfile.REACTION.key
            "def_" in allKeys -> ArtifactOptimizationProfile.DEFENSE.key
            "support" in build || "buffer" in build || "shielder" in build ||
                "healer" in build || "heal_" in allKeys ->
                ArtifactOptimizationProfile.ENERGY_SUPPORT.key
            "hp_" in allKeys -> ArtifactOptimizationProfile.HP.key
            else -> ArtifactOptimizationProfile.ATTACK.key
        }
    }

    private fun sourceLastUpdated(text: String): String? =
        Regex("""Last updated on:\s*([A-Za-z]+ \d{1,2}, \d{4} \d{1,2}:\d{2} [AP]M)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanText)
            ?.takeIf(String::isNotBlank)

    private fun String.substringBeforeMainStats(): String {
        val marker = Regex(
            """<b[^>]*>\s*Main Stats:?\s*</b>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(this)
        return marker?.let { substring(0, it.range.first) } ?: this
    }

    private fun slug(value: String): String =
        GoodKeyNormalizer.normalize(value).ifBlank { "build" }

    private fun cleanText(value: String): String =
        value.replace('\u00a0', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

    private val ELEMENT_DAMAGE_KEYS = mapOf(
        "physical" to "physical_dmg_",
        "pyro" to "pyro_dmg_",
        "hydro" to "hydro_dmg_",
        "electro" to "electro_dmg_",
        "cryo" to "cryo_dmg_",
        "anemo" to "anemo_dmg_",
        "geo" to "geo_dmg_",
        "dendro" to "dendro_dmg_",
    )
}

class CharacterIndex private constructor(
    private val byKey: Map<String, CharacterMetadata>,
    private val byName: Map<String, CharacterMetadata>,
) {
    fun find(name: String): CharacterMetadata? {
        val normalizedName = GoodKeyNormalizer.normalize(name)
        return byName[normalizedName]
            ?: CHARACTER_ALIASES[normalizedName]?.let(byKey::get)
    }

    companion object {
        fun empty(): CharacterIndex = CharacterIndex(emptyMap(), emptyMap())

        fun load(directory: Path): CharacterIndex {
            val mapper = jacksonObjectMapper()
            val listFile = directory.resolve("char_list.json")
            if (!Files.isRegularFile(listFile)) return empty()

            val characterKeys = mapper.readValue(
                listFile.toFile(),
                Array<String>::class.java,
            )
            val metadata = characterKeys.mapNotNull { key ->
                val dataFile = directory.resolve("data").resolve("$key.json")
                if (!Files.isRegularFile(dataFile)) return@mapNotNull null
                val root = mapper.readTree(dataFile.toFile())
                CharacterMetadata(
                    key = key,
                    name = root.text("name") ?: return@mapNotNull null,
                    element = root.text("elementText"),
                )
            }
            return CharacterIndex(
                byKey = metadata.associateBy { GoodKeyNormalizer.normalize(it.key) },
                byName = metadata.associateBy { GoodKeyNormalizer.normalize(it.name) },
            )
        }
    }
}

data class CharacterMetadata(
    val key: String,
    val name: String,
    val element: String?,
)

private data class ScraperOptions(
    val sourceUrl: String,
    val input: Path?,
    val output: Path,
    val characterDataDirectory: Path,
) {
    companion object {
        fun parse(args: List<String>): ScraperOptions {
            var sourceUrl = DEFAULT_SOURCE_URL
            var input: Path? = null
            var output = Path.of(DEFAULT_OUTPUT)
            var characterData = Path.of(DEFAULT_CHARACTER_DATA)
            var index = 0
            while (index < args.size) {
                when (val option = args[index]) {
                    "--url" -> sourceUrl = args.valueAfter(index, option).also { index++ }
                    "--input" -> input = Path.of(args.valueAfter(index, option)).also { index++ }
                    "--output" -> output = Path.of(args.valueAfter(index, option)).also { index++ }
                    "--character-data" ->
                        characterData = Path.of(args.valueAfter(index, option)).also { index++ }
                    else -> throw IllegalArgumentException("Unknown option: $option")
                }
                index++
            }
            return ScraperOptions(
                sourceUrl = sourceUrl,
                input = input,
                output = output,
                characterDataDirectory = characterData,
            )
        }

        private fun List<String>.valueAfter(index: Int, option: String): String =
            getOrNull(index + 1)
                ?: throw IllegalArgumentException("$option requires a value")
    }
}

private fun fetchHtml(url: String): String =
    Jsoup.connect(url)
        .userAgent(
            "Mozilla/5.0 (compatible; GenshinArchiveBuildScraper/1.0; " +
                "+https://game8.co/)",
        )
        .timeout(30_000)
        .get()
        .outerHtml()

private fun JsonNode.text(field: String): String? =
    path(field).takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()

private val CHARACTER_ALIASES = mapOf(
    "ayaka" to "kamisatoayaka",
    "ayato" to "kamisatoayato",
    "itto" to "aratakiitto",
    "kazuha" to "kaedeharakazuha",
    "kokomi" to "sangonomiyakokomi",
    "raiden" to "raidenshogun",
    "raidenshogun" to "raidenshogun",
    "sara" to "kujousara",
    "heizou" to "shikanoinheizou",
    "shinobu" to "kukishinobu",
    "childe" to "tartaglia",
    "tartagliachilde" to "tartaglia",
    "yae" to "yaemiko",
    "yaemiko" to "yaemiko",
)
