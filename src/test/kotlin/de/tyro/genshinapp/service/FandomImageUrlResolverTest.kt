package de.tyro.genshinapp.service

import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.CharacterImageType
import kotlin.test.Test
import kotlin.test.assertEquals

class FandomImageUrlResolverTest {
    private val resolver = FandomImageUrlResolver(GenshinContentProperties())

    @Test
    fun `calculates the MediaWiki hash paths for all Aino image types`() {
        assertEquals(
            "https://static.wikia.nocookie.net/gensin-impact/images/a/a3/Aino_Icon.png",
            resolver.characterImageUrl("Aino", CharacterImageType.ICON),
        )
        assertEquals(
            "https://static.wikia.nocookie.net/gensin-impact/images/e/e5/Aino_Card.png",
            resolver.characterImageUrl("Aino", CharacterImageType.CARD),
        )
        assertEquals(
            "https://static.wikia.nocookie.net/gensin-impact/images/c/c8/Character_Aino_Full_Wish.png",
            resolver.characterImageUrl("Aino", CharacterImageType.WISH),
        )
    }

    @Test
    fun `calculates the MediaWiki hash path for item images`() {
        assertEquals(
            "https://static.wikia.nocookie.net/gensin-impact/images/8/84/Item_Mora.png",
            resolver.itemImageUrl("Mora"),
        )
        assertEquals(
            "https://static.wikia.nocookie.net/gensin-impact/images/0/04/Item_Crown_of_Insight.png",
            resolver.itemImageUrl("Crown of Insight"),
        )
        assertEquals(
            "https://static.wikia.nocookie.net/gensin-impact/images/4/40/Item_Gilded_Corsage.png",
            resolver.itemImageUrl("Gilded Corsage"),
        )
    }

    @Test
    fun `calculates the MediaWiki hash path for weapon images`() {
        assertEquals(
            "https://static.wikia.nocookie.net/gensin-impact/images/4/4f/Weapon_Wolf%27s_Gravestone.png",
            resolver.weaponImageUrl("Wolf's Gravestone"),
        )
    }
}
