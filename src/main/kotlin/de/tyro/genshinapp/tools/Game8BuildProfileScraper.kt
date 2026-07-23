package de.tyro.genshinapp.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.service.ArtifactOptimizationProfile
import de.tyro.genshinapp.service.ArtifactOptimizerBuildCatalog
import de.tyro.genshinapp.service.ArtifactOptimizerBuildGoalRange
import de.tyro.genshinapp.service.ArtifactOptimizerBuildGoalStatRecommendation
import de.tyro.genshinapp.service.ArtifactOptimizerBuildProfile
import de.tyro.genshinapp.service.ArtifactOptimizerBuildSetRecommendation
import de.tyro.genshinapp.service.ArtifactOptimizerBuildStatRecommendation
import de.tyro.genshinapp.service.ArtifactOptimizerBuildTeamLineup
import de.tyro.genshinapp.service.ArtifactOptimizerBuildTeamMember
import de.tyro.genshinapp.service.ArtifactOptimizerBuildTeamRecommendation
import de.tyro.genshinapp.service.ArtifactOptimizerBuildTeamSlot
import de.tyro.genshinapp.service.ArtifactOptimizerBuildWeaponRecommendation
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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
    val baseCatalog = Game8BuildProfileParser.parse(
        html = html,
        sourceUrl = options.sourceUrl,
        scrapedAt = Instant.now().toString(),
        characterIndex = characterIndex,
    )
    val catalog = if (options.fetchCharacterPages) {
        val characterPages = fetchCharacterPages(baseCatalog)
        Game8BuildProfileParser.withCharacterPageDetails(
            baseCatalog,
            characterPages,
            characterIndex,
        )
    } else {
        baseCatalog
    }

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

    fun withCharacterPageDetails(
        catalog: ArtifactOptimizerBuildCatalog,
        characterPages: Map<String, String>,
        characterIndex: CharacterIndex = CharacterIndex.empty(),
    ): ArtifactOptimizerBuildCatalog {
        val detailsByUrl = characterPages.mapValues { (url, html) ->
            val details = Game8CharacterPageParser.parse(html, url)
            details.copy(
                teams = details.teams.map { team ->
                    team.copy(
                        lineups = team.lineups.map { lineup ->
                            lineup.copy(
                                members = lineup.members.map { member ->
                                    member.withCharacterKey(characterIndex)
                                },
                                slots = lineup.slots.map { slot ->
                                    slot.copy(
                                        members = slot.members.map { member ->
                                            member.withCharacterKey(characterIndex)
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        return catalog.copy(
            profiles = catalog.profiles.map { profile ->
                val details = profile.characterUrl?.let(detailsByUrl::get)
                    ?: return@map profile
                profile.copy(
                    goalStats = details.goalStats,
                    goalNotes = details.notes,
                    recommendedWeapons = details.weapons,
                    recommendedTeams = details.teams,
                )
            },
        )
    }

    private fun ArtifactOptimizerBuildTeamMember.withCharacterKey(
        characterIndex: CharacterIndex,
    ): ArtifactOptimizerBuildTeamMember {
        val baseName = name.substringBefore(" (").trim()
        val normalizedName = GoodKeyNormalizer.normalize(baseName)
        val characterKey = when (normalizedName) {
            "traveler", "aether", "lumine" -> "traveler"
            else -> characterIndex.find(baseName)?.key
        }
        return copy(characterKey = characterKey)
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

    fun statRecommendation(
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
            "elemental mastery" in normalized || normalized == "em" -> add("eleMas")
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

object Game8CharacterPageParser {
    fun parse(html: String, sourceUrl: String = ""): Game8CharacterBuildDetails {
        val document = Jsoup.parse(html, sourceUrl)
        val tables = document.select("table").filter(::isGoalStatTable)
        val goalStats = tables.flatMap(::parseGoalStats).distinctBy {
            it.stat.lowercase() to it.goalValue.lowercase()
        }
        val notes = tables.flatMap(::followingParagraphs).distinct()
        return Game8CharacterBuildDetails(
            goalStats = goalStats,
            notes = notes,
            weapons = parseWeapons(document),
            teams = parseTeams(document),
        )
    }

    private fun parseWeapons(document: Document):
        List<ArtifactOptimizerBuildWeaponRecommendation> {
        val recommendations = document.select("table").flatMap { table ->
            val section = precedingHeading(table, setOf("h2"))?.text().orEmpty()
            if ("weapon" !in section.lowercase()) return@flatMap emptyList()
            parseWeaponTable(table)
        }.ifEmpty {
            parseBuildSummaryWeapons(document)
        }
        val merged = linkedMapOf<String, ArtifactOptimizerBuildWeaponRecommendation>()
        recommendations.forEach { recommendation ->
            val key = GoodKeyNormalizer.normalize(recommendation.name)
            if (key.isBlank() || key in GENERIC_WEAPON_LABELS) return@forEach
            val current = merged[key]
            merged[key] = if (current == null) {
                recommendation.copy(rank = merged.size + 1)
            } else {
                current.copy(
                    url = current.url ?: recommendation.url,
                    category = preferredWeaponCategory(current.category, recommendation.category),
                    obtainMethod = current.obtainMethod ?: recommendation.obtainMethod,
                )
            }
        }
        return merged.values.toList()
    }

    private fun parseWeaponTable(table: Element):
        List<ArtifactOptimizerBuildWeaponRecommendation> {
        val rows = table.select("tr")
        val headerIndex = rows.indexOfFirst { row ->
            row.select("> th, > td").any { isWeaponHeader(cleanText(it.text())) }
        }
        if (headerIndex < 0) return emptyList()
        val headers = rows[headerIndex].select("> th, > td").map { cleanText(it.text()) }
        val weaponIndex = headers.indexOfFirst(::isWeaponHeader)
        if (weaponIndex < 0 || headers.size < 2) return emptyList()
        val obtainIndex = headers.indexOfFirst {
            val normalized = it.lowercase()
            "how to get" in normalized || "obtain" in normalized
        }
        val category = precedingHeading(table, HEADING_TAGS)?.text()
            ?.let(::cleanText)
            ?.takeIf(String::isNotBlank)
        return rows.drop(headerIndex + 1).mapNotNull { row ->
            val cells = row.select("> th, > td")
            val weaponCell = cells.getOrNull(weaponIndex) ?: return@mapNotNull null
            val namedLink = namedLinks(weaponCell).firstOrNull()
            val name = namedLink?.first ?: elementName(weaponCell)
            if (name.isBlank()) return@mapNotNull null
            ArtifactOptimizerBuildWeaponRecommendation(
                name = name,
                url = namedLink?.second,
                category = category,
                obtainMethod = cells.getOrNull(obtainIndex)
                    ?.let { cleanText(it.text()) }
                    ?.takeIf(String::isNotBlank),
            )
        }
    }

    private fun parseBuildSummaryWeapons(
        document: Document,
    ): List<ArtifactOptimizerBuildWeaponRecommendation> = document.select("table")
        .flatMap tableLoop@ { table ->
            table.select("tr").flatMap rowLoop@ { row ->
                val cells = row.select("> th, > td")
                val label = cells.firstOrNull()?.text()?.let(::cleanText)
                    ?.lowercase().orEmpty()
                if (label != "best weapon" && label != "replacement weapons") {
                    return@rowLoop emptyList()
                }
                val valueCell = cells.getOrNull(1) ?: return@rowLoop emptyList()
                val links = namedLinks(valueCell)
                if (links.isNotEmpty()) {
                    links.map { (name, url) ->
                        ArtifactOptimizerBuildWeaponRecommendation(name = name, url = url)
                    }
                } else {
                    NUMBERED_RECOMMENDATION.split(cleanText(valueCell.text()))
                        .map(::cleanText)
                        .filter(String::isNotBlank)
                        .map { ArtifactOptimizerBuildWeaponRecommendation(name = it) }
                }
            }
        }

    private fun parseTeams(document: Document):
        List<ArtifactOptimizerBuildTeamRecommendation> = document.select("table")
            .mapNotNull tableLoop@ { table ->
                val section = precedingHeading(table, setOf("h2"))?.text().orEmpty()
                if ("team" !in section.lowercase()) return@tableLoop null
                parseRoleBasedTeam(table) ?: parseTeamOptionPool(table)
            }

    private fun parseRoleBasedTeam(table: Element): ArtifactOptimizerBuildTeamRecommendation? {
        val rows = table.select("tr")
        val headerIndex = rows.indexOfFirst { row ->
            val labels = row.select("> th, > td").map { cleanText(it.text()) }
            labels.size >= 3 && labels.all(::isTeamRole)
        }
        if (headerIndex < 0) return null
        val roles = rows[headerIndex].select("> th, > td")
            .map { cleanText(it.text()) }
        val lineups = rows.drop(headerIndex + 1).mapNotNull rowLoop@ { row ->
            val cells = row.select("> th, > td")
            if (cells.size != roles.size) return@rowLoop null
            val slots = roles.zip(cells).mapNotNull { (role, cell) ->
                teamSlot(role, cell)
            }
            ArtifactOptimizerBuildTeamLineup(slots = slots)
                .takeIf { it.slots.isNotEmpty() }
        }
        if (lineups.isEmpty()) return null
        return ArtifactOptimizerBuildTeamRecommendation(
            name = teamHeading(table),
            lineups = lineups,
            notes = followingParagraphs(table),
        )
    }

    private fun parseTeamOptionPool(table: Element): ArtifactOptimizerBuildTeamRecommendation? {
        val rows = table.select("tr")
        val titleIndex = rows.indexOfFirst { row ->
            val cells = row.select("> th, > td")
            val titleDescribesTeam = "team" in cleanText(
                cells.singleOrNull()?.text().orEmpty(),
            ).lowercase() || "team" in teamHeading(table).lowercase()
            cells.size == 1 && titleDescribesTeam &&
                cells[0].attr("colspan").toIntOrNull()?.let { it >= 3 } == true
        }
        if (titleIndex < 0) return null
        val lineup = rows.drop(titleIndex + 1).firstNotNullOfOrNull { row ->
            val cells = row.select("> th, > td")
            if (cells.size !in 3..5) return@firstNotNullOfOrNull null
            val slots = cells.mapIndexedNotNull { index, cell ->
                teamSlot("Slot ${index + 1}", cell)
            }
            ArtifactOptimizerBuildTeamLineup(slots = slots)
                .takeIf { it.slots.size == cells.size }
        } ?: return null
        return ArtifactOptimizerBuildTeamRecommendation(
            name = teamHeading(table),
            lineups = listOf(lineup),
            notes = tableSummaryNotes(table),
        )
    }

    private fun teamSlot(role: String, cell: Element): ArtifactOptimizerBuildTeamSlot? {
        val names = namedLinks(cell).ifEmpty {
            elementName(cell).takeIf(String::isNotBlank)
                ?.let { listOf(it to null) }
                .orEmpty()
        }
        val members = names.map { (name, url) ->
            ArtifactOptimizerBuildTeamMember(
                name = name,
                url = url,
            )
        }
        return ArtifactOptimizerBuildTeamSlot(role, members)
            .takeIf { it.members.isNotEmpty() }
    }

    private fun teamHeading(table: Element): String = precedingHeading(
        table,
        setOf("h3", "h4", "h5", "h6"),
    )?.text()?.let(::cleanText)?.takeIf(String::isNotBlank) ?: "Recommended team"

    private fun tableSummaryNotes(table: Element): List<String> = table.select("tr")
        .mapNotNull { row ->
            val cells = row.select("> th, > td")
            val cell = cells.singleOrNull() ?: return@mapNotNull null
            val text = cleanText(cell.text())
            if (!text.startsWith("Team Summary", ignoreCase = true)) {
                return@mapNotNull null
            }
            text.drop("Team Summary".length)
                .substringBefore("Alternate Teammates")
                .trim()
                .takeIf(String::isNotBlank)
        }

    private fun preferredWeaponCategory(current: String?, candidate: String?): String? = when {
        current.isNullOrBlank() -> candidate
        candidate.isNullOrBlank() -> current
        isFreeToPlayCategory(current) -> current
        isFreeToPlayCategory(candidate) -> candidate
        else -> current
    }

    private fun isFreeToPlayCategory(value: String): Boolean =
        "free-to-play" in value.lowercase() || "f2p" in value.lowercase()

    private fun isWeaponHeader(value: String): Boolean {
        val normalized = value.lowercase()
        return normalized == "weapon" || normalized == "recommended weapons" ||
            normalized == "recommended weapon"
    }

    private fun isTeamRole(value: String): Boolean {
        val normalized = value.lowercase()
        return TEAM_ROLE_MARKERS.any { it in normalized }
    }

    private fun namedLinks(element: Element): List<Pair<String, String?>> {
        val links = element.select("a[href]").mapNotNull { link ->
            val name = cleanText(link.text()).ifBlank {
                link.selectFirst("img[alt]")?.attr("alt")?.let(::cleanMediaName).orEmpty()
            }
            name.takeIf(String::isNotBlank)?.let {
                it to link.absUrl("href").takeIf(String::isNotBlank)
            }
        }
        return links.distinctBy { GoodKeyNormalizer.normalize(it.first) }
    }

    private fun elementName(element: Element): String = cleanText(element.text()).ifBlank {
        element.selectFirst("img[alt]")?.attr("alt")?.let(::cleanMediaName).orEmpty()
    }

    private fun cleanMediaName(value: String): String = cleanText(value)
        .removePrefix("Genshin Impact - ")
        .removePrefix("Genshin - ")
        .removeSuffix(" Image")
        .removeSuffix(" Icon")
        .trim()

    private fun precedingHeading(element: Element, tags: Set<String>): Element? {
        val elements = element.ownerDocument()?.allElements ?: return null
        val index = elements.indexOf(element)
        if (index < 0) return null
        return elements.take(index).lastOrNull { it.tagName() in tags }
    }

    private fun isGoalStatTable(table: Element): Boolean =
        table.select("tr").any { row ->
            val headers = row.select("> th, > td").map { cleanText(it.text()).lowercase() }
            headers.size >= 2 && headers[0] == "stat" && headers[1] == "goal value"
        }

    private fun parseGoalStats(table: Element): List<ArtifactOptimizerBuildGoalStatRecommendation> {
        val rows = table.select("tr")
        val headerIndex = rows.indexOfFirst { row ->
            val cells = row.select("> th, > td").map { cleanText(it.text()).lowercase() }
            cells.size >= 2 && cells[0] == "stat" && cells[1] == "goal value"
        }
        if (headerIndex < 0) return emptyList()
        return rows.drop(headerIndex + 1).mapNotNull { row ->
            val cells = row.select("> th, > td")
            if (cells.size < 2) return@mapNotNull null
            val stat = cleanText(cells[0].text())
            val goalValue = multilineText(cells[1])
            if (stat.isBlank() || goalValue.isBlank()) return@mapNotNull null
            ArtifactOptimizerBuildGoalStatRecommendation(
                stat = stat,
                goalValue = goalValue,
                keys = Game8BuildProfileParser.statRecommendation(stat, null).keys,
                ranges = parseRanges(goalValue),
            )
        }
    }

    private fun parseRanges(goalValue: String): List<ArtifactOptimizerBuildGoalRange> {
        val ranges = mutableListOf<ArtifactOptimizerBuildGoalRange>()
        goalValue.lines().map(String::trim).filter(String::isNotBlank).forEach { line ->
            val parsed = parseRange(line)
            if (parsed != null) {
                ranges += parsed
            } else if (ranges.isNotEmpty()) {
                val previous = ranges.removeLast()
                ranges += previous.copy(
                    condition = listOfNotNull(previous.condition, cleanCondition(line))
                        .filter(String::isNotBlank)
                        .joinToString(" ")
                        .takeIf(String::isNotBlank),
                )
            }
        }
        return ranges
    }

    private fun parseRange(value: String): ArtifactOptimizerBuildGoalRange? {
        val rangeMatch = RANGE_VALUE.find(value)
        if (rangeMatch != null) {
            val suffix = value.substring(rangeMatch.range.last + 1)
            val openEnded = OPEN_ENDED_SUFFIX.find(suffix)
            return ArtifactOptimizerBuildGoalRange(
                minimum = rangeMatch.groupValues[1].numberValue(),
                maximum = if (openEnded == null) {
                    rangeMatch.groupValues[2].numberValue()
                } else {
                    null
                },
                condition = cleanCondition(
                    openEnded?.let { suffix.removeRange(it.range) } ?: suffix,
                ).takeIf(String::isNotBlank),
            )
        }
        val upperMatch = UPPER_BOUND_VALUE.find(value)
        if (upperMatch != null) {
            return ArtifactOptimizerBuildGoalRange(
                maximum = upperMatch.groupValues[1].numberValue(),
                condition = conditionAfter(value, upperMatch.range.last + 1),
            )
        }
        val lowerMatch = LOWER_BOUND_VALUE.find(value)
        if (lowerMatch != null) {
            return ArtifactOptimizerBuildGoalRange(
                minimum = lowerMatch.groupValues[1].numberValue(),
                condition = conditionAfter(value, lowerMatch.range.last + 1),
            )
        }
        val exactMatch = EXACT_VALUE.find(value) ?: return null
        val exact = exactMatch.groupValues[1].numberValue()
        return ArtifactOptimizerBuildGoalRange(
            minimum = exact,
            maximum = exact,
            condition = conditionAfter(value, exactMatch.range.last + 1),
        )
    }

    private fun followingParagraphs(table: Element): List<String> {
        val elements = table.ownerDocument()?.allElements ?: return emptyList()
        val tableIndex = elements.indexOf(table)
        if (tableIndex < 0) return emptyList()
        return elements.drop(tableIndex + 1)
            .takeWhile { it.tagName() !in HEADING_TAGS }
            .filter { it.tagName() == "p" && it.parents().none { parent -> parent == table } }
            .map { cleanText(it.text()) }
            .filter(String::isNotBlank)
    }

    private fun multilineText(element: Element): String {
        val withBreaks = element.clone()
        withBreaks.select("br, hr").forEach { it.before("\n") }
        return withBreaks.wholeText()
            .replace('\u00a0', ' ')
            .lines()
            .map(::cleanText)
            .filter(String::isNotBlank)
            .joinToString("\n")
    }

    private fun conditionAfter(value: String, index: Int): String? =
        cleanCondition(value.substring(index)).takeIf(String::isNotBlank)

    private fun cleanCondition(value: String): String = value
        .trim()
        .removePrefix("%")
        .trim()
        .removeSurrounding("(", ")")
        .trim(' ', '-', '–', '—', ':', ';', ',')

    private fun String.numberValue(): Double? = replace(",", "").toDoubleOrNull()

    private fun cleanText(value: String): String = value
        .replace('\u00a0', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()

    private val NUMBER = """(\d[\d,]*(?:\.\d+)?)"""
    private val VALUE_UNIT = """(?:%|HP|ATK|DEF|EM)?"""
    private val RANGE_VALUE = Regex(
        """$NUMBER\s*$VALUE_UNIT\s*(?:~|[-–—])\s*$NUMBER\s*$VALUE_UNIT""",
        RegexOption.IGNORE_CASE,
    )
    private val LOWER_BOUND_VALUE = Regex(
        """$NUMBER\s*$VALUE_UNIT\s*~?\s*""" +
            """(?:\+|or\s+(?:more|above|higher)|and\s+(?:above|higher))""",
        RegexOption.IGNORE_CASE,
    )
    private val UPPER_BOUND_VALUE = Regex(
        """(?:up\s+to|below|under|less\s+than)\s*$NUMBER\s*$VALUE_UNIT""",
        RegexOption.IGNORE_CASE,
    )
    private val EXACT_VALUE = Regex("""$NUMBER\s*$VALUE_UNIT""", RegexOption.IGNORE_CASE)
    private val OPEN_ENDED_SUFFIX = Regex(
        """^\s*(?:\+|(?:or|and)\s+(?:more|above|higher))""",
        RegexOption.IGNORE_CASE,
    )
    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val TEAM_ROLE_MARKERS = setOf(
        "dps",
        "support",
        "driver",
        "healer",
        "shielder",
        "flex",
    )
    private val NUMBERED_RECOMMENDATION = Regex("""\s*\d+\.\s*""")
    private val GENERIC_WEAPON_LABELS = setOf("weapon", "weapons", "recommendedweapons")
}

data class Game8CharacterBuildDetails(
    val goalStats: List<ArtifactOptimizerBuildGoalStatRecommendation> = emptyList(),
    val notes: List<String> = emptyList(),
    val weapons: List<ArtifactOptimizerBuildWeaponRecommendation> = emptyList(),
    val teams: List<ArtifactOptimizerBuildTeamRecommendation> = emptyList(),
)

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
    val fetchCharacterPages: Boolean,
) {
    companion object {
        fun parse(args: List<String>): ScraperOptions {
            var sourceUrl = DEFAULT_SOURCE_URL
            var input: Path? = null
            var output = Path.of(DEFAULT_OUTPUT)
            var characterData = Path.of(DEFAULT_CHARACTER_DATA)
            var fetchCharacterPages = true
            var index = 0
            while (index < args.size) {
                when (val option = args[index]) {
                    "--url" -> sourceUrl = args.valueAfter(index, option).also { index++ }
                    "--input" -> input = Path.of(args.valueAfter(index, option)).also { index++ }
                    "--output" -> output = Path.of(args.valueAfter(index, option)).also { index++ }
                    "--character-data" ->
                        characterData = Path.of(args.valueAfter(index, option)).also { index++ }
                    "--skip-character-pages" -> fetchCharacterPages = false
                    else -> throw IllegalArgumentException("Unknown option: $option")
                }
                index++
            }
            return ScraperOptions(
                sourceUrl = sourceUrl,
                input = input,
                output = output,
                characterDataDirectory = characterData,
                fetchCharacterPages = fetchCharacterPages,
            )
        }

        private fun List<String>.valueAfter(index: Int, option: String): String =
            getOrNull(index + 1)
                ?: throw IllegalArgumentException("$option requires a value")
    }
}

private fun fetchCharacterPages(
    catalog: ArtifactOptimizerBuildCatalog,
): Map<String, String> = catalog.profiles
    .mapNotNull(ArtifactOptimizerBuildProfile::characterUrl)
    .distinct()
    .mapNotNull { url ->
        runCatching {
            println("Fetching Game8 character guidance: $url")
            url to fetchHtml(url)
        }.onFailure { error ->
            System.err.println("Could not fetch $url: ${error.message}")
        }.getOrNull()
    }
    .toMap()

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
