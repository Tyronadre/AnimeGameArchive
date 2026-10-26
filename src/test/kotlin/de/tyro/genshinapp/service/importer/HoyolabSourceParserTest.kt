package de.tyro.genshinapp.service.importer

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.model.*
import kotlin.test.*

class HoyolabSourceParserTest {
    private val mapper = jacksonObjectMapper()
    private val parser = HoyolabSourceParser(mapper)
    private fun fixture(id: Int) = requireNotNull(javaClass.getResourceAsStream("/hoyowiki-sources/$id.json"))
        .use(mapper::readTree)

    @Test
    fun `real old and new boss detail types override broad list filters`() {
        assertEquals(MaterialSourceType.WORLD_BOSS, parser.enemy(fixture(2163)).type)
        assertEquals(MaterialSourceType.WORLD_BOSS, parser.enemy(fixture(8992)).type)
        val weekly = parser.enemy(fixture(10781))
        assertEquals(MaterialSourceType.WEEKLY_BOSS, weekly.type)
        assertTrue(weekly.drops.any { it.hoyolabPageId == 10782 })
        assertTrue(parser.enemy(fixture(2163)).drops.any { it.hoyolabPageId == 1751 })
    }

    @Test
    fun `common elite and local legend use structured loot only`() {
        for (id in listOf(2241, 2220, 11433)) assertEquals(MaterialSourceType.ENEMY, parser.enemy(fixture(id)).type)
        val slime = parser.enemy(fixture(2241))
        assertEquals(setOf(757, 396, 398, 397), slime.drops.map { it.hoyolabPageId }.toSet())
        assertTrue(slime.drops.any { it.name?.any { char -> char.code > 127 } == true })
    }

    @Test
    fun `old and new talent families expose challenge names and schedules`() {
        val old = parser.talent(fixture(164))
        assertEquals("Frosted Altar", old.name)
        assertEquals(MaterialSchedule.MONDAY_THURSDAY, old.schedule)
        assertEquals("talent:freedom", old.family)
        val new = parser.talent(fixture(8582))
        assertEquals("Fallen City", new.name)
        assertEquals("Nod-Krai", new.region)
    }

    @Test
    fun `unknown schedule remains unknown and malformed loot is rejected`() {
        val book = fixture(164).deepCopy<ObjectNode>()
        val component = book.path("modules")[0].path("components")[0] as ObjectNode
        component.put("data", component.path("data").asText().replace("Monday/Thursday/Sunday", "Friday"))
        assertNull(parser.talent(book).schedule)
        val enemy = fixture(2241).deepCopy<ObjectNode>()
        val loot = enemy.path("modules")[0].path("components")[1] as ObjectNode
        loot.put("data", "{\"list\":[\"broken\"]}")
        assertFailsWith<IllegalArgumentException> { parser.enemy(enemy) }
    }
}
