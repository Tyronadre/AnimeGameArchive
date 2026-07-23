package de.tyro.genshinapp.tools

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Game8BuildProfileParserTest {
    @Test
    fun `parses Game8 build rows into optimizer profiles`() {
        val html = """
            <html>
            <body>
            <p>Last updated on: March 7, 2026 10:25 PM Hot: Version 6.5</p>
            <table>
              <thead>
                <tr>
                  <th data-cell="character">Character</th>
                  <th colspan="2" data-cell="build">Build</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td class="center">
                    <a href="https://game8.co/games/Genshin-Impact/archives/297525">Ayaka</a>
                  </td>
                  <td class="top">
                    <b class="a-bold">DPS</b><hr>
                    <div class="align"><a href="/weapon">Mistsplitter Reforged</a></div>
                    <div class="align"><a href="/set">Blizzard Strayer</a> (4-pc)</div>
                    <hr><b>Main Stats:</b><br>
                    <div class="align"><img alt="Genshin - Hourglass">: ATK%</div>
                    <div class="align"><img alt="Genshin - Goblet">: Cryo DMG Bonus</div>
                    <div class="align"><img alt="Genshin - Circlet">: CRIT Rate or CRIT DMG</div>
                    <hr><b>Sub Stats:</b><br>
                    &bull; CRIT DMG<br>&bull; CRIT Rate<br>&bull; Energy Recharge
                  </td>
                  <td class="top">
                    <b class="a-bold">Freeze Support</b><hr>
                    <div class="align"><a href="/set">Noblesse Oblige</a> (4-pc)</div>
                    <hr><b>Main Stats:</b><br>
                    <div class="align"><img alt="Genshin - Hourglass">: Elemental Mastery</div>
                    <div class="align"><img alt="Genshin - Goblet">: Elemental Mastery</div>
                    <div class="align"><img alt="Genshin - Circlet">: Elemental Mastery</div>
                    <hr><b>Sub Stats</b><br>
                    &bull; Elemental Mastery<br>&bull; Energy Recharge
                  </td>
                </tr>
              </tbody>
            </table>
            </body>
            </html>
        """.trimIndent()

        val catalog = Game8BuildProfileParser.parse(
            html = html,
            sourceUrl = "https://game8.co/games/Genshin-Impact/archives/530535",
            scrapedAt = "2026-07-07T00:00:00Z",
            characterIndex = CharacterIndex.load(Path.of("src/main/resources/data/characters")),
        )

        assertEquals("March 7, 2026 10:25 PM", catalog.sourceLastUpdated)
        assertEquals(2, catalog.profiles.size)

        val dps = catalog.profiles[0]
        assertEquals("kamisatoayaka", dps.characterKey)
        assertEquals("DPS", dps.buildName)
        assertEquals("attack", dps.profileKey)
        assertEquals("Mistsplitter Reforged", dps.weaponName)
        assertEquals("blizzardstrayer", dps.artifactSets.single().key)
        assertEquals(4, dps.artifactSets.single().count)
        assertEquals("atk_", dps.fixedMainStats["sands"])
        assertEquals("cryo_dmg_", dps.fixedMainStats["goblet"])
        assertNull(dps.fixedMainStats["circlet"])
        assertEquals(listOf("critDMG_", "critRate_", "enerRech_"), dps.substatKeys)

        val support = catalog.profiles[1]
        assertEquals("reaction", support.profileKey)
        assertEquals("noblesseoblige", support.artifactSets.single().key)
        assertEquals("eleMas", support.fixedMainStats["circlet"])
    }

    @Test
    fun `adds explicit character goal tables and guidance to optimizer profiles`() {
        val listingHtml = """
            <table>
              <thead><tr>
                <th data-cell="character">Character</th>
                <th data-cell="build">Build</th>
              </tr></thead>
              <tbody><tr>
                <td><a href="https://game8.co/games/Genshin-Impact/archives/537903">Aino</a></td>
                <td>
                  <b class="a-bold">Support</b>
                  <b>Main Stats:</b>
                  <div class="align"><img alt="Hourglass">: Elemental Mastery</div>
                  <div class="align"><img alt="Goblet">: Elemental Mastery</div>
                  <div class="align"><img alt="Circlet">: CRIT Rate or CRIT DMG</div>
                  <b>Sub Stats:</b><br>
                  &bull; Elemental Mastery<br>&bull; Energy Recharge
                </td>
              </tr></tbody>
            </table>
        """.trimIndent()
        val characterHtml = """
            <html><body>
              <h4>Aino Goal Stat Values</h4>
              <div class="a-table"><table>
                <thead><tr><th>Stat</th><th>Goal Value</th></tr></thead>
                <tbody>
                  <tr><td>Elemental Mastery</td><td>700~800</td></tr>
                  <tr><td>Energy Recharge</td><td>
                    150~180% (if Solo Hydro)<hr>110~130% (if Double Hydro)
                  </td></tr>
                  <tr><td>CRIT Rate</td><td>50~70%</td></tr>
                  <tr><td>CRIT DMG</td><td>100~120%</td></tr>
                </tbody>
              </table></div>
              <p>Aino's main goal stat would be Elemental Mastery to maximize her
                 reaction damage and Elemental Burst scaling. Energy Recharge to ensure
                 consistent Burst uptime.</p>
              <p>Optional stats after EM and ER are CRIT Rate and CRIT DMG.</p>
              <h3>Aino Talent Priority</h3>
              <p>This paragraph belongs to the next section.</p>

              <h2>Aino Best Weapons</h2>
              <h3>Best Weapons for Aino</h3>
              <table>
                <tr><th></th><th>Weapon</th><th>Weapon Information</th></tr>
                <tr>
                  <td>1st</td>
                  <td><a href="/flame-forged-insight">Flame-Forged Insight</a></td>
                  <td>Best in slot.</td>
                </tr>
                <tr>
                  <td>2nd</td>
                  <td><a href="/master-key">Master Key</a></td>
                  <td>Energy Recharge option.</td>
                </tr>
              </table>
              <h3>Best Free-to-Play Weapon for Aino</h3>
              <table>
                <tr><th>Weapon</th><th>Weapon Information</th></tr>
                <tr>
                  <td><a href="/favonius-greatsword">Favonius Greatsword</a></td>
                  <td>Generates particles.</td>
                </tr>
              </table>
              <h3>All Recommended Weapons for Aino</h3>
              <table>
                <tr><th>Recommended Weapons</th><th>How to Get</th></tr>
                <tr><td><a href="/flame-forged-insight">Flame-Forged Insight</a></td>
                    <td>Event</td></tr>
                <tr><td><a href="/master-key">Master Key</a></td><td>Crafted</td></tr>
                <tr><td><a href="/favonius-greatsword">Favonius Greatsword</a></td>
                    <td>Gacha</td></tr>
                <tr><td><a href="/sacrificial-greatsword">Sacrificial Greatsword</a></td>
                    <td>Gacha</td></tr>
              </table>

              <h2>Aino Best Team Comps</h2>
              <h3>Aino's Notable Teammates</h3>
              <table>
                <tr><th>Character</th><th>Explanation</th></tr>
                <tr><td>Flins</td><td>A notable teammate, but not a full team.</td></tr>
              </table>
              <h3>Aino Lunar Charged Team</h3>
              <table>
                <tr><th>Support</th><th>Main DPS</th><th>Sub-DPS/Support</th>
                    <th>Support</th></tr>
                <tr>
                  <td><a href="/aino">Aino</a></td>
                  <td><a href="/flins">Flins</a></td>
                  <td><a href="/ineffa">Ineffa</a></td>
                  <td><a href="/sucrose">Sucrose</a></td>
                </tr>
              </table>
              <p>Aino provides Hydro while Flins drives Lunar-Charged damage.</p>
              <h3>Aino Bloom Teams</h3>
              <table>
                <tr><th>Support</th><th>Main DPS/Driver</th><th>Sub-DPS/Support</th>
                    <th>Support</th></tr>
                <tr><td>Aino</td><td>Nahida</td><td>Nilou</td><td>Baizhu</td></tr>
                <tr><td>Aino</td><td>Alhaitham</td><td>Nahida</td><td>Nilou</td></tr>
              </table>
              <p>These are two proposed Bloom lineups.</p>
              <h3>Aino Flexible Teams</h3>
              <table>
                <tr><th colspan="4">Aino Flexible</th></tr>
                <tr>
                  <td>Aino</td>
                  <td><a href="/flins">Flins</a><hr><a href="/nefer">Nefer</a></td>
                  <td><a href="/ineffa">Ineffa</a></td>
                  <td><a href="/sucrose">Sucrose</a><hr><a href="/baizhu">Baizhu</a></td>
                </tr>
                <tr><td colspan="4"><b>Team Summary</b><br>
                    Choose one character from each slot based on the reaction.</td></tr>
              </table>
            </body></html>
        """.trimIndent()
        val characterIndex = CharacterIndex.load(
            Path.of("src/main/resources/data/characters"),
        )
        val baseCatalog = Game8BuildProfileParser.parse(
            html = listingHtml,
            characterIndex = characterIndex,
        )

        val catalog = Game8BuildProfileParser.withCharacterPageDetails(
            baseCatalog,
            mapOf(
                "https://game8.co/games/Genshin-Impact/archives/537903" to characterHtml,
            ),
            characterIndex,
        )

        val profile = catalog.profiles.single()
        assertEquals(4, profile.goalStats.size)
        assertEquals(listOf("eleMas", "enerRech_", "critRate_", "critDMG_"), profile.goalStatKeys)
        assertEquals("700~800", profile.goalStats[0].goalValue)
        assertEquals(700.0, profile.goalStats[0].primaryRange?.minimum)
        assertEquals(800.0, profile.goalStats[0].primaryRange?.maximum)

        val energyRecharge = profile.goalStats[1]
        assertEquals(
            "150~180% (if Solo Hydro)\n110~130% (if Double Hydro)",
            energyRecharge.goalValue,
        )
        assertEquals(2, energyRecharge.ranges.size)
        assertEquals(150.0, energyRecharge.ranges[0].minimum)
        assertEquals(180.0, energyRecharge.ranges[0].maximum)
        assertEquals("if Solo Hydro", energyRecharge.ranges[0].condition)
        assertEquals(110.0, energyRecharge.ranges[1].minimum)
        assertEquals(130.0, energyRecharge.ranges[1].maximum)
        assertEquals("if Double Hydro", energyRecharge.ranges[1].condition)
        assertEquals(150.0, profile.goalMinimumTargets["enerRech_"])
        assertEquals(180.0, profile.goalMaximumTargets["enerRech_"])

        assertEquals(2, profile.goalNotes.size)
        assertTrue(profile.goalNotes.first().startsWith("Aino's main goal stat"))
        assertTrue(profile.goalNotes.none { "next section" in it })

        assertEquals(
            listOf(
                "Flame-Forged Insight",
                "Master Key",
                "Favonius Greatsword",
                "Sacrificial Greatsword",
            ),
            profile.recommendedWeapons.map { it.name },
        )
        assertEquals(listOf(1, 2, 3, 4), profile.recommendedWeapons.map { it.rank })
        assertEquals("Event", profile.recommendedWeapons[0].obtainMethod)
        assertEquals("Crafted", profile.recommendedWeapons[1].obtainMethod)
        assertTrue(
            profile.recommendedWeapons[2].category
                ?.contains("Free-to-Play") == true,
        )

        assertEquals(3, profile.recommendedTeams.size)
        val lunarCharged = profile.recommendedTeams[0]
        assertEquals("Aino Lunar Charged Team", lunarCharged.name)
        assertEquals(1, lunarCharged.lineups.size)
        assertEquals(
            listOf("Aino", "Flins", "Ineffa", "Sucrose"),
            lunarCharged.lineups.single().displaySlots.flatMap { slot ->
                slot.members.map { it.name }
            },
        )
        assertEquals(
            listOf("aino", "flins", "ineffa", "sucrose"),
            lunarCharged.lineups.single().displaySlots.flatMap { slot ->
                slot.members.map { it.characterKey }
            },
        )
        assertEquals(
            listOf("Support", "Main DPS", "Sub-DPS/Support", "Support"),
            lunarCharged.lineups.single().displaySlots.map { it.role },
        )
        assertEquals(1, lunarCharged.notes.size)
        assertEquals(2, profile.recommendedTeams[1].lineups.size)
        val flexible = profile.recommendedTeams[2]
        assertEquals("Aino Flexible Teams", flexible.name)
        assertEquals(listOf(1, 2, 1, 2), flexible.lineups.single().slots.map {
            it.members.size
        })
        assertEquals(
            "Choose one character from each slot based on the reaction.",
            flexible.notes.single(),
        )
        assertTrue(profile.recommendedTeams.none { "Notable" in it.name })
    }

    @Test
    fun `parses open ended goal values without inventing a cap`() {
        val html = """
            <table>
              <tr><th>Stat</th><th>Goal Value</th></tr>
              <tr><td>HP</td><td>40,000 or above</td></tr>
              <tr><td>CRIT DMG</td><td>175% or Above</td></tr>
              <tr><td>Elemental Mastery</td><td>100 - 200</td></tr>
              <tr><td>HP</td><td>30,000 HP or above</td></tr>
              <tr><td>CRIT DMG</td><td>200%~ or above</td></tr>
              <tr><td>ATK</td><td>2,500 ~ 3,000+</td></tr>
              <tr><td>EM</td><td>1,000</td></tr>
            </table>
        """.trimIndent()

        val details = Game8CharacterPageParser.parse(html)

        assertEquals(40_000.0, details.goalStats[0].primaryRange?.minimum)
        assertNull(details.goalStats[0].primaryRange?.maximum)
        assertEquals(175.0, details.goalStats[1].primaryRange?.minimum)
        assertNull(details.goalStats[1].primaryRange?.maximum)
        assertEquals(100.0, details.goalStats[2].primaryRange?.minimum)
        assertEquals(200.0, details.goalStats[2].primaryRange?.maximum)
        assertEquals(30_000.0, details.goalStats[3].primaryRange?.minimum)
        assertNull(details.goalStats[3].primaryRange?.maximum)
        assertEquals(200.0, details.goalStats[4].primaryRange?.minimum)
        assertNull(details.goalStats[4].primaryRange?.maximum)
        assertEquals(2_500.0, details.goalStats[5].primaryRange?.minimum)
        assertNull(details.goalStats[5].primaryRange?.maximum)
        assertEquals(listOf("eleMas"), details.goalStats[6].keys)
    }
}
