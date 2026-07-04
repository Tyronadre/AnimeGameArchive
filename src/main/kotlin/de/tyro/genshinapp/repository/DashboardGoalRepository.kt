package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.DashboardGoal
import org.springframework.data.jpa.repository.JpaRepository

interface DashboardGoalRepository : JpaRepository<DashboardGoal, Long> {
    fun findAllByUser_Id(userId: Long): List<DashboardGoal>
}
