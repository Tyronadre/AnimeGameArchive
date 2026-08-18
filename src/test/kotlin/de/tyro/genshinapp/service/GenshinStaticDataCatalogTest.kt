package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.repository.GenshinStaticDataRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import kotlin.test.Test
import kotlin.test.assertEquals

@DataJpaTest
class GenshinStaticDataCatalogTest(
    @Autowired private val repository: GenshinStaticDataRepository,
) {
    private val objectMapper = jacksonObjectMapper()
    private val catalog = GenshinStaticDataCatalog(objectMapper, repository)

    @Test
    fun `creates updates compares and removes static records idempotently`() {
        val first = catalog.synchronize(
            "artifacts",
            listOf(
                json("""{"name":"Set A","version":"1.0","2pc":"ATK +18%"}"""),
                json("""{"name":"Set B","version":"1.0","2pc":"HP +20%"}"""),
            ),
        )
        val unchanged = catalog.synchronize(
            "artifacts",
            listOf(
                json("""{"2pc":"ATK +18%","version":"1.0","name":"Set A"}"""),
                json("""{"name":"Set B","version":"1.0","2pc":"HP +20%"}"""),
            ),
        )
        val changed = catalog.synchronize(
            "artifacts",
            listOf(
                json("""{"name":"Set A","version":"1.1","2pc":"ATK +20%"}"""),
                json("""{"name":"Set C","version":"1.0","2pc":"ER +20%"}"""),
            ),
        )

        assertEquals(2, first.createdCount)
        assertEquals(0, unchanged.changedCount)
        assertEquals(2, unchanged.unchangedCount)
        assertEquals(1, changed.createdCount)
        assertEquals(1, changed.updatedCount)
        assertEquals(1, changed.removedCount)
        assertEquals(listOf("Set A", "Set C"), catalog.readFolder("artifacts").map {
            it.path("name").asText()
        })
    }

    private fun json(value: String) = objectMapper.readTree(value)
}
