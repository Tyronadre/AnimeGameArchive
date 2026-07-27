package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.MaterialSource
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface MaterialSourceRepository : JpaRepository<MaterialSource, Long> {
    fun findByCatalogKey(catalogKey: String): MaterialSource?

    @EntityGraph(attributePaths = ["materials", "materials.material"])
    fun findAllBySourceTypeInOrderByDisplayOrderAscNameAsc(
        sourceTypes: Collection<String>,
    ): List<MaterialSource>
}
