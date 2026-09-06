package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.BuildStatTable
import org.springframework.data.jpa.repository.JpaRepository

interface BuildStatTableRepository : JpaRepository<BuildStatTable, String>
