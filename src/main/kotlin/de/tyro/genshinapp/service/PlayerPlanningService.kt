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
import de.tyro.genshinapp.model.TravelerIdentity
import org.springframework.stereotype.Service

@Service
class PlayerPlanningService(
    private val catalogService: CharacterCatalogService,
    private val materialCalculator: MaterialCalculator,
    private val materialCraftingService: MaterialCraftingService,
    private val travelerService: TravelerService? = null,
) {
    fun createPlan(
        snapshot: PlayerSnapshot,
        targets: Map<String, CharacterTargetValues> = emptyMap(),
        userId: Long? = null,
        includeUnownedCharacters: Boolean = false,
    ): PlayerMaterialPlan {
        val catalog = catalogService.getCharacters()
        val catalogByKey = catalog.associateBy {
            GoodKeyNormalizer.normalize(it.key)
        }
        val unmatched = mutableListOf<String>()
        val travelerSelection = userId?.let { travelerService?.selection(it) }

        fun findCharacter(normalizedKey: String): CharacterDefinition? =
            if (normalizedKey == TravelerIdentity.KEY && travelerSelection != null) {
                catalogService.findTraveler(
                    travelerSelection.element,
                    travelerSelection.appearance,
                )
            } else {
                catalogByKey[normalizedKey] ?: catalogService.findCharacter(normalizedKey)
            }

        fun savedTarget(normalizedKey: String, character: CharacterDefinition) =
            targets[normalizedKey] ?: targets[GoodKeyNormalizer.normalize(character.key)]

        val candidates = linkedMapOf<String, Pair<CharacterDefinition, PlayerCharacterState>>()
        snapshot.characters.forEach { state ->
            val normalizedKey = TravelerIdentity.canonicalCharacterKey(state.key)
            val character = findCharacter(normalizedKey)
            if (character == null) {
                unmatched += state.key
            } else {
                candidates.putIfAbsent(normalizedKey, character to state)
            }
        }
        catalog.forEach { catalogCharacter ->
            val normalizedKey = TravelerIdentity.canonicalCharacterKey(catalogCharacter.key)
            if (normalizedKey in candidates) return@forEach
            val character = findCharacter(normalizedKey) ?: return@forEach
            val target = savedTarget(normalizedKey, character)
            if (!includeUnownedCharacters && target?.owned != true) return@forEach
            candidates[normalizedKey] = character to PlayerCharacterState(
                key = character.key,
                level = 1,
                constellation = 0,
                ascension = 0,
                normalTalent = 1,
                skillTalent = 1,
                burstTalent = 1,
            )
        }

        val characterPlans = candidates.mapNotNull { (normalizedKey, candidate) ->
            val (character, state) = candidate
            val form = CharacterProgressForm().also {
                if (normalizedKey == TravelerIdentity.KEY && travelerSelection != null) {
                    it.applyShared(state)
                } else {
                    it.apply(state)
                }
            }
            val savedTarget = savedTarget(normalizedKey, character)
            if (normalizedKey == TravelerIdentity.KEY && travelerSelection != null) {
                savedTarget?.applySharedTo(form)
                travelerService?.progress(userId, travelerSelection.element)?.applyTo(form)
            } else {
                savedTarget?.applyTo(form)
            }
            if (includeUnownedCharacters) {
                form.owned = true
                form.ownershipExplicit = true
            }
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
        val normalizedCharacterKey = TravelerIdentity.canonicalCharacterKey(characterKey)
        return snapshot.characters.find { state ->
            TravelerIdentity.canonicalCharacterKey(state.key) == normalizedCharacterKey
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
