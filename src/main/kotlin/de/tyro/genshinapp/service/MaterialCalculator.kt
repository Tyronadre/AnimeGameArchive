package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterProgress
import de.tyro.genshinapp.model.MaterialCost
import de.tyro.genshinapp.model.MaterialRequirement
import org.springframework.stereotype.Service

@Service
class MaterialCalculator(
    private val materialCatalogService: MaterialCatalogService,
) {
    fun calculate(
        character: CharacterDefinition,
        progress: CharacterProgress,
    ): List<MaterialRequirement> {
        val totals = linkedMapOf<MaterialKey, Long>()

        ((progress.ascension + 1)..progress.targetAscension)
            .flatMap { character.ascensionCosts[it].orEmpty() }
            .forEach { totals.add(it) }

        addTalentCosts(character, progress.normalTalent, progress.targetNormalTalent, totals)
        addTalentCosts(character, progress.skillTalent, progress.targetSkillTalent, totals)
        addTalentCosts(character, progress.burstTalent, progress.targetBurstTalent, totals)

        if (progress.targetLevel > progress.level) {
            val experience = experienceNeeded(progress.level, progress.targetLevel)
            totals.add(MaterialKey(0, "Character EXP"), experience.toLong())
            totals.add(MaterialKey(202, "Mora"), experience / 5L)
        }

        return totals.entries
            .sortedWith(compareBy({ it.key.id }, { it.key.name }))
            .map { (material, amount) ->
                MaterialRequirement(
                    id = material.id,
                    name = material.name,
                    amount = amount,
                    imageUrl = materialCatalogService.materialImageUrl(material.id),
                )
            }
    }

    private fun addTalentCosts(
        character: CharacterDefinition,
        currentLevel: Int,
        targetLevel: Int,
        totals: MutableMap<MaterialKey, Long>,
    ) {
        ((currentLevel + 1)..targetLevel)
            .flatMap { character.talentCosts[it].orEmpty() }
            .forEach { totals.add(it) }
    }

    private fun MutableMap<MaterialKey, Long>.add(cost: MaterialCost) =
        add(MaterialKey(cost.id, cost.name), cost.count)

    private fun MutableMap<MaterialKey, Long>.add(key: MaterialKey, amount: Long) {
        this[key] = getOrDefault(key, 0L) + amount
    }

    private data class MaterialKey(val id: Int, val name: String)

    companion object {
        private val experiencePerLevel = intArrayOf(
            1000, 1325, 1700, 2150, 2625, 3150, 3725, 4350, 5000, 5700,
            6450, 7225, 8050, 8925, 9825, 10750, 11725, 12725, 13775, 14875,
            16800, 18000, 19250, 20550, 21875, 23250, 24650, 26100, 27575, 29100,
            30650, 32250, 33875, 35550, 37250, 38975, 40750, 42575, 44425, 46300,
            50625, 52700, 54775, 56900, 59075, 61275, 63525, 65800, 68125, 70475,
            76500, 79050, 81650, 84275, 86950, 89650, 92400, 95175, 98000, 100875,
            108950, 112050, 115175, 118325, 121525, 124775, 128075, 131400, 134775, 138175,
            148700, 152375, 156075, 159825, 163600, 167425, 171300, 175225, 179175, 183175,
            216225, 243025, 273100, 306800, 344600, 386950, 434425, 487625, 547200,
        )

        fun experienceNeeded(fromLevel: Int, toLevel: Int): Int {
            require(fromLevel in 1..90 && toLevel in fromLevel..90)
            return (fromLevel until toLevel).sumOf { experiencePerLevel[it - 1] }
        }
    }
}
