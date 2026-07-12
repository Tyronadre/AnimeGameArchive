package de.tyro.genshinapp.tools

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
