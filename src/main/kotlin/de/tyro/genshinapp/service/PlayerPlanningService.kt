package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterProgress
import de.tyro.genshinapp.model.CharacterProgressForm
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.InventoryMaterialBalance
import de.tyro.genshinapp.model.MaterialRequirement
import de.tyro.genshinapp.model.PlayerCharacterPlan
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerMaterialPlan
import de.tyro.genshinapp.model.PlayerSnapshot
import org.springframework.stereotype.Service

@Service
class PlayerPlanningService(
    private val catalogService: CharacterCatalogService,
    private val materialCalculator: MaterialCalculator,
    private val materialCraftingService: MaterialCraftingService,
) {
    fun createPlan(
        snapshot: PlayerSnapshot,
        targets: Map<String, CharacterTargetValues> = emptyMap(),
    ): PlayerMaterialPlan {
        val catalogByKey = catalogService.getCharacters().associateBy {
            GoodKeyNormalizer.normalize(it.key)
        }
        val unmatched = mutableListOf<String>()

        val characterPlans = snapshot.characters.mapNotNull { state ->
            val normalizedKey = GoodKeyNormalizer.normalize(state.key)
            val character = when (normalizedKey) {
                TRAVELER_KEY -> catalogByKey[AETHER_KEY]
                else -> catalogByKey[normalizedKey] ?: catalogService.findCharacter(normalizedKey)
            }
            if (character == null) {
                unmatched += state.key
                return@mapNotNull null
            }

            val form = CharacterProgressForm().also { it.apply(state) }
            val savedTarget = targets[normalizedKey]
                ?: targets[GoodKeyNormalizer.normalize(character.key)]
            savedTarget?.applyTo(form)
            val progress = form.normalized()
            if (!progress.owned) return@mapNotNull null

            createCharacterPlan(character, state, snapshot, progress)
        }

        val aggregateRequirements = linkedMapOf<MaterialIdentity, AggregatedRequirement>()
        characterPlans
            .flatMap(PlayerCharacterPlan::materials)
            .forEach { material ->
                val key = MaterialIdentity(material.id, material.name)
                val aggregate = aggregateRequirements.getOrPut(key) {
                    AggregatedRequirement(material.id, material.name, material.imageUrl)
                }
                aggregate.required = Math.addExact(aggregate.required, material.required)
            }

        val aggregateMaterials = calculateBalances(
            aggregateRequirements.values.map { requirement ->
                MaterialRequirement(
                    id = requirement.id,
                    name = requirement.name,
                    amount = requirement.required,
                    imageUrl = requirement.imageUrl,
                )
            },
            snapshot,
        )
            .sortedWith(
                compareBy<InventoryMaterialBalance> { if (it.missing > 0) 0 else 1 }
                    .thenBy { it.name },
            )

        return PlayerMaterialPlan(
            snapshot = snapshot,
            characters = characterPlans,
            aggregateMaterials = aggregateMaterials,
            unmatchedCharacterKeys = unmatched,
        )
    }

    fun findCharacterState(
        snapshot: PlayerSnapshot,
        characterKey: String,
    ): PlayerCharacterState? {
        val normalizedCharacterKey = GoodKeyNormalizer.normalize(characterKey)
        return snapshot.characters.find { state ->
            val normalizedStateKey = GoodKeyNormalizer.normalize(state.key)
            normalizedStateKey == normalizedCharacterKey ||
                (
                    normalizedStateKey == TRAVELER_KEY &&
                        normalizedCharacterKey in setOf(AETHER_KEY, LUMINE_KEY)
                    )
        }
    }

    fun createCharacterPlan(
        character: CharacterDefinition,
        state: PlayerCharacterState,
        snapshot: PlayerSnapshot,
        progress: CharacterProgress = defaultProgress(state),
    ): PlayerCharacterPlan {
        val materials = calculateBalances(character, progress, snapshot)

        return PlayerCharacterPlan(
            character = character,
            state = state,
            materials = materials,
        )
    }

    fun calculateBalances(
        character: CharacterDefinition,
        progress: CharacterProgress,
        snapshot: PlayerSnapshot?,
    ): List<InventoryMaterialBalance> = calculateBalances(
        materialCalculator.calculate(character, progress),
        snapshot,
    )

    private fun defaultProgress(state: PlayerCharacterState): CharacterProgress {
        val safeAscension = maxOf(
            state.ascension,
            CharacterProgress.minimumAscensionFor(state.level),
        )
        return CharacterProgress(
            owned = true,
            level = state.level,
            ascension = safeAscension,
            constellation = state.constellation,
            normalTalent = state.normalTalent,
            skillTalent = state.skillTalent,
            burstTalent = state.burstTalent,
            targetLevel = maxOf(DEFAULT_TARGET_LEVEL, state.level),
            targetAscension = maxOf(DEFAULT_TARGET_ASCENSION, safeAscension),
            targetNormalTalent = maxOf(DEFAULT_TARGET_TALENT, state.normalTalent),
            targetSkillTalent = maxOf(DEFAULT_TARGET_TALENT, state.skillTalent),
            targetBurstTalent = maxOf(DEFAULT_TARGET_TALENT, state.burstTalent),
        )
    }

    fun calculateBalances(
        requirements: List<MaterialRequirement>,
        snapshot: PlayerSnapshot?,
    ): List<InventoryMaterialBalance> {
        val balances = requirements.map { requirement ->
            balance(requirement, snapshot)
        }
        return if (snapshot == null) {
            materialCraftingService.applyCrafting(balances, emptyMap())
        } else {
            materialCraftingService.applyCrafting(balances, snapshot.inventory)
        }
    }

    private fun balance(
        requirement: MaterialRequirement,
        snapshot: PlayerSnapshot?,
    ): InventoryMaterialBalance {
        val id = requirement.id
        val name = requirement.name
        val owned = if (snapshot == null) {
            0L
        } else if (id == CHARACTER_EXPERIENCE_ID) {
            characterExperience(snapshot)
        } else {
            snapshot.inventory.getOrDefault(GoodKeyNormalizer.normalize(name), 0L)
        }
        return InventoryMaterialBalance(
            id = id,
            name = name,
            required = requirement.amount,
            owned = owned,
            missing = (requirement.amount - owned).coerceAtLeast(0L),
            imageUrl = requirement.imageUrl,
        )
    }

    private fun characterExperience(snapshot: PlayerSnapshot): Long {
        fun quantity(key: String): Long = snapshot.inventory.getOrDefault(key, 0L)
        return Math.addExact(
            Math.addExact(
                Math.multiplyExact(quantity(HEROS_WIT_KEY), HEROS_WIT_EXPERIENCE),
                Math.multiplyExact(
                    quantity(ADVENTURERS_EXPERIENCE_KEY),
                    ADVENTURERS_EXPERIENCE,
                ),
            ),
            Math.multiplyExact(quantity(WANDERERS_ADVICE_KEY), WANDERERS_ADVICE_EXPERIENCE),
        )
    }

    private data class MaterialIdentity(
        val id: Int,
        val name: String,
    )

    private data class AggregatedRequirement(
        val id: Int,
        val name: String,
        val imageUrl: String?,
        var required: Long = 0,
    )

    companion object {
        private const val TRAVELER_KEY = "traveler"
        private const val AETHER_KEY = "aether"
        private const val LUMINE_KEY = "lumine"
        private const val CHARACTER_EXPERIENCE_ID = 0
        private const val HEROS_WIT_KEY = "heroswit"
        private const val ADVENTURERS_EXPERIENCE_KEY = "adventurersexperience"
        private const val WANDERERS_ADVICE_KEY = "wanderersadvice"
        private const val HEROS_WIT_EXPERIENCE = 20_000L
        private const val ADVENTURERS_EXPERIENCE = 5_000L
        private const val WANDERERS_ADVICE_EXPERIENCE = 1_000L
        private const val DEFAULT_TARGET_LEVEL = 80
        private const val DEFAULT_TARGET_ASCENSION = 6
        private const val DEFAULT_TARGET_TALENT = 9
    }
}
