package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.GameArtifactMainStat
import org.springframework.data.jpa.repository.JpaRepository

interface GameArtifactMainStatRepository : JpaRepository<GameArtifactMainStat, String>
