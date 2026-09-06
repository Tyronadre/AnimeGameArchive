package de.tyro.genshinapp.service.importer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HoyolabPageParserTest {
    @Test
    fun `bare material page references are retained for catalog resolution`() {
        val mapper = jacksonObjectMapper()
        val node = mapper.nodeFactory.textNode("\$[{\"ep_id\":164,\"amount\":3}]\$")

        val reference = HoyolabPageParser(mapper).materialReferences(node).single()

        assertEquals(164, reference.hoyolabPageId)
        assertEquals(3, reference.amount)
        assertNull(reference.name)
    }

    @Test
    fun `material catalog entries survive missing icons`() {
        val summary = HoyolabCatalogEntryMapper().toMaterialSummary(
            HoyolabWikiEntry(entryPageId = 164, name = "Teachings of Freedom"),
        )

        assertNotNull(summary)
        assertEquals(164, summary.entryPageId)
        assertNull(summary.iconUrl)
    }

    @Test
    fun `legacy hoyolab icon urls with spaces are normalized`() {
        val summary = HoyolabCatalogEntryMapper().toMaterialSummary(
            HoyolabWikiEntry(
                entryPageId = 1319,
                name = "Runic Fang",
                iconUrl = "https://bbs.hoyolab.com/hoyowiki/picture/object/Runic Fang_icon.png",
            ),
        )

        assertNotNull(summary)
        assertEquals(
            "https://bbs.hoyolab.com/hoyowiki/picture/object/Runic%20Fang_icon.png",
            summary.iconUrl,
        )
    }
}
