package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.CharacterImageType
import de.tyro.genshinapp.model.FightProperty
import de.tyro.genshinapp.service.content.DynamicContentLoader
import de.tyro.genshinapp.service.content.FandomImageUrlResolver
import de.tyro.genshinapp.service.importer.HoyolabCharacterSummary
import org.mockito.Mockito.mock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CharacterCatalogServiceTest {
    @Test
    fun `builds the character catalogue from HoYoWiki summaries`() {
        val service = CharacterCatalogService(
            mock(DynamicContentLoader::class.java),
            FandomImageUrlResolver(GenshinContentProperties()),
        )

        assertEquals(
            1,
            service.synchronizeHoyolabCharacterList(
                listOf(
                    HoyolabCharacterSummary(
                        8894,
                        "Nefer",
                        rarity = 5,
                        weapon = "Catalyst",
                        element = "Dendro",
                        ascensionStat = FightProperty.FIGHT_PROP_CRITICAL_HURT,
                    ),
                ),
            ),
        )
        val nefer = assertNotNull(service.findCharacter("nefer"))
        assertEquals("Nefer", nefer.name)
        assertEquals("Catalyst", nefer.weapon)
        assertEquals(8894, nefer.hoyolabEntryId)
        assertEquals(
            "https://static.wikia.nocookie.net/gensin-impact/images/5/5b/Nefer_Icon.png",
            nefer.remoteImageUrl(CharacterImageType.ICON),
        )
        assertEquals(
            "https://static.wikia.nocookie.net/gensin-impact/images/9/9a/Nefer_Card.png",
            nefer.remoteImageUrl(CharacterImageType.CARD),
        )
        assertEquals(
            "https://static.wikia.nocookie.net/gensin-impact/images/8/85/Character_Nefer_Full_Wish.png",
            nefer.remoteImageUrl(CharacterImageType.WISH),
        )
    }
}
