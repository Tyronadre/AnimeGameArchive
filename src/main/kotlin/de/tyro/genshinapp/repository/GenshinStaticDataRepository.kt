package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.GenshinStaticData
import org.springframework.data.jpa.repository.JpaRepository

interface GenshinStaticDataRepository : JpaRepository<GenshinStaticData, Long> {
    fun findAllByFolderOrderByNameAsc(folder: String): List<GenshinStaticData>
}
