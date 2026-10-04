package de.tyro.genshinapp.service.artifact

import de.tyro.genshinapp.service.player.GoodImportServiceTest
import de.tyro.genshinapp.service.catalog.ArtifactCatalogService
import de.tyro.genshinapp.service.player.GoodImportService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.PlayerArtifactStat
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerSnapshot
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtifactOptimizationServiceTest {
    private val objectMapper = jacksonObjectMapper()
    private val importService = GoodImportService(objectMapper)
    private val service = ArtifactOptimizationService(
        ArtifactCatalogService(objectMapper),
    )

    @Test
    fun `useful rolls score higher than flat rolls for an attack build`() {
        val useful = artifact(
            substats = listOf(
                PlayerArtifactStat("critRate_", 10.5),
                PlayerArtifactStat("critDMG_", 21.0),
                PlayerArtifactStat("atk_", 11.7),
                PlayerArtifactStat("enerRech_", 5.8),
            ),
        )
        val flat = artifact(
            substats = listOf(
                PlayerArtifactStat("hp", 1_000.0),
                PlayerArtifactStat("def", 70.0),
                PlayerArtifactStat("atk", 55.0),
                PlayerArtifactStat("hp_", 10.0),
            ),
        )

        val usefulScore = service.evaluate(useful, ArtifactOptimizationProfile.ATTACK)
        val flatScore = service.evaluate(flat, ArtifactOptimizationProfile.ATTACK)

        assertTrue(usefulScore.score > flatScore.score)
        assertTrue(usefulScore.weightedRolls > flatScore.weightedRolls)
        assertTrue(usefulScore.grade in listOf("S", "A", "B"))
    }

    @Test
    fun `quality remains independent of equipped stats and buffs`() {
        val flower = artifact(
            slotKey = "flower",
            mainStatKey = "hp",
            substats = listOf(
                PlayerArtifactStat("atk_", 4.1),
                PlayerArtifactStat("critRate_", 8.9),
                PlayerArtifactStat("critDMG_", 13.2),
                PlayerArtifactStat("enerRech_", 10.4),
            ),
        )
        val targets = service.createTargets(
            profile = ArtifactOptimizationProfile.ATTACK,
            custom = true,
            requestedPriorityStats = listOf("critRate_", "critDMG_", "eleMas"),
        )

        val flowerValue = service.evaluate(
            flower,
            ArtifactOptimizationProfile.ATTACK,
            targets,
            contextTotals = mapOf("critRate_" to 88.1),
        ).weightedRolls
        val sameFlowerValue = service.evaluate(
            flower,
            ArtifactOptimizationProfile.ATTACK,
            targets,
            contextTotals = mapOf("critRate_" to 100.0, "critDMG_" to 250.0, "enerRech_" to 300.0),
        ).weightedRolls

        assertTrue(flowerValue > 0.0)
        assertEquals(flowerValue, sameFlowerValue)
    }

    @Test
    fun `quality rewards total useful stat value while reporting roll tiers separately`() {
        val highTierArtifact = artifact(
            slotKey = "flower",
            mainStatKey = "hp",
            substats = listOf(
                PlayerArtifactStat("eleMas", 46.62),
                PlayerArtifactStat("critRate_", 3.5),
                PlayerArtifactStat("critDMG_", 5.44),
                PlayerArtifactStat("hp", 209.13),
            ),
        ).copy(totalRolls = 5)
        val lowTierArtifact = artifact(
            slotKey = "flower",
            mainStatKey = "hp",
            substats = listOf(
                PlayerArtifactStat("eleMas", 18.65),
                PlayerArtifactStat("critRate_", 5.44),
                PlayerArtifactStat("critDMG_", 10.88),
                PlayerArtifactStat("hp", 209.13),
            ),
        ).copy(totalRolls = 6)
        val targets = service.createTargets(
            profile = ArtifactOptimizationProfile.ATTACK,
            custom = true,
            requestedPriorityStats = listOf("critRate_", "critDMG_", "eleMas"),
        )

        val highTier = service.evaluate(
            highTierArtifact,
            ArtifactOptimizationProfile.ATTACK,
            targets,
            contextTotals = mapOf("critRate_" to 95.0),
        )
        val lowTier = service.evaluate(
            lowTierArtifact,
            ArtifactOptimizationProfile.ATTACK,
            targets,
            contextTotals = mapOf("critRate_" to 95.0),
        )

        assertEquals(listOf(4, 4), highTier.rollAnalysis.substats.getValue("eleMas").tiers)
        assertEquals(listOf(1, 1), lowTier.rollAnalysis.substats.getValue("critRate_").tiers)
        assertEquals(0.86, highTier.rollAnalysis.quality, 0.01)
        assertEquals(0.72, lowTier.rollAnalysis.quality, 0.01)
        assertTrue(lowTier.score > highTier.score)
    }

    @Test
    fun `custom main stats and substats override profile recommendations`() {
        val targetedPiece = artifact(
            slotKey = "sands",
            mainStatKey = "hp_",
            substats = listOf(
                PlayerArtifactStat("hp", 896.0),
                PlayerArtifactStat("def", 69.0),
                PlayerArtifactStat("atk", 19.0),
                PlayerArtifactStat("enerRech_", 6.5),
            ),
        )
        val profilePiece = artifact(
            slotKey = "sands",
            mainStatKey = "atk_",
            substats = listOf(
                PlayerArtifactStat("critRate_", 10.5),
                PlayerArtifactStat("critDMG_", 21.0),
                PlayerArtifactStat("enerRech_", 12.9),
                PlayerArtifactStat("atk", 19.0),
            ),
        )
        val targets = service.createTargets(
            profile = ArtifactOptimizationProfile.ATTACK,
            custom = true,
            requestedMainStats = mapOf(
                "flower" to "hp_",
                "plume" to "atk_",
                "sands" to "hp_",
            ),
            requestedSubstats = listOf("hp", "def"),
        )

        val targetedScore = service.evaluate(
            targetedPiece,
            ArtifactOptimizationProfile.ATTACK,
            targets,
        )
        val profileScore = service.evaluate(
            profilePiece,
            ArtifactOptimizationProfile.ATTACK,
            targets,
        )

        assertTrue(targetedScore.score > profileScore.score)
        assertEquals(1.0, targetedScore.mainStatFit)
        assertEquals(0.0, profileScore.mainStatFit)
        assertEquals(setOf("hp", "def"), targets.substatKeys)
        assertEquals(setOf("sands", "goblet", "circlet"), targets.mainStats.keys)
        assertTrue("flower" !in targets.mainStats)
        assertTrue("plume" !in targets.mainStats)
    }

    @Test
    fun `main stat compatibility is separate from substat quality`() {
        val damageGoblet = artifact(
            mainStatKey = "pyro_dmg_",
            substats = listOf(
                PlayerArtifactStat("hp", 299.0),
                PlayerArtifactStat("def", 23.0),
            ),
        )
        val mismatchedGoblet = artifact(
            mainStatKey = "hp_",
            substats = listOf(
                PlayerArtifactStat("critRate_", 11.7),
                PlayerArtifactStat("critDMG_", 23.3),
                PlayerArtifactStat("atk_", 11.7),
            ),
        )

        val targets = service.createTargets(
            ArtifactOptimizationProfile.ATTACK,
            custom = true,
            requestedMainStats = mapOf("goblet" to "pyro_dmg_"),
        )
        val damageScore = service.evaluate(damageGoblet, ArtifactOptimizationProfile.ATTACK, targets)
        val mismatchScore = service.evaluate(mismatchedGoblet, ArtifactOptimizationProfile.ATTACK, targets)

        assertEquals(0.0, damageScore.weightedRolls)
        assertEquals(0.0, damageScore.score)
        assertEquals(1.0, damageScore.mainStatFit)
        assertEquals(0.0, mismatchScore.mainStatFit)
        assertTrue(mismatchScore.score > damageScore.score)
    }

    @Test
    fun `custom set bonuses constrain the calculated loadout`() {
        val snapshot = importService.parse(
            Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT),
        )
        val character = snapshot.characters.first { it.key == "Tartaglia" }
        val equipped = snapshot.artifacts.filter {
            GoodKeyNormalizer.normalize(it.location.orEmpty()) ==
                GoodKeyNormalizer.normalize(character.key)
        }
        val profile = service.inferProfile(equipped)
        val setSelection = service.createSetSelection(
            modeKey = "custom",
            requestedTargets = listOf(ArtifactSetTarget("HeartOfDepth", 4)),
            availableSetKeys = snapshot.artifacts.map(PlayerArtifact::setKey),
        )

        val result = service.optimize(
            snapshot = snapshot,
            character = character,
            profile = profile,
            setSelection = setSelection,
        )

        assertEquals("heartofdepth", result.setPlan.requirements.single().setKey)
        assertEquals(4, result.setPlan.requirements.single().count)
        result.loadout?.let { loadout ->
            val heartOfDepthPieces = loadout.pieces.count {
                GoodKeyNormalizer.normalize(it.artifact.setKey) == "heartofdepth"
            }
            assertTrue(heartOfDepthPieces >= 4)
        }
    }

    @Test
    fun `set target validation supports two plus two and rejects four plus two`() {
        val availableSets = listOf("HeartOfDepth", "GladiatorsFinale")
        val twoPlusTwo = service.createSetSelection(
            modeKey = "custom",
            requestedTargets = listOf(
                ArtifactSetTarget("HeartOfDepth", 2),
                ArtifactSetTarget("GladiatorsFinale", 2),
            ),
            availableSetKeys = availableSets,
        )
        val fourPlusTwo = service.createSetSelection(
            modeKey = "custom",
            requestedTargets = listOf(
                ArtifactSetTarget("HeartOfDepth", 4),
                ArtifactSetTarget("GladiatorsFinale", 2),
            ),
            availableSetKeys = availableSets,
        )

        assertEquals(listOf(2, 2), twoPlusTwo.requirements.map(ArtifactSetTarget::count))
        assertEquals(listOf(4), fourPlusTwo.requirements.map(ArtifactSetTarget::count))
    }

    @Test
    fun `orders priorities sanitizes ranges and limits optimizer bonus stats`() {
        val targets = service.createTargets(
            profile = ArtifactOptimizationProfile.ATTACK,
            custom = true,
            requestedPriorityStats = listOf(
                "critRate_",
                "critDMG_",
                "eleMas",
                "critRate_",
                "invalid",
            ),
            requestedMinimumTargets = mapOf(
                "critRate_" to 120.0,
                "enerRech_" to 150.0,
                "invalid" to 999.0,
            ),
            requestedMaximumTargets = mapOf(
                "critDMG_" to 220.0,
                "enerRech_" to 180.0,
            ),
            requestedAdditionalStats = mapOf(
                "atk_" to 25.0,
                "enerRech_" to -20.0,
                "critDMG_" to 30.0,
                "invalid" to 500.0,
            ),
            additionalCritRate = 140.0,
        )

        assertEquals(listOf("critRate_", "critDMG_", "eleMas"), targets.substatPriorities)
        assertEquals(100.0, targets.minimumTargets["critRate_"])
        assertEquals(150.0, targets.minimumTargets["enerRech_"])
        assertTrue("invalid" !in targets.minimumTargets)
        assertEquals(100.0, targets.maximumTargets["critRate_"])
        assertEquals(220.0, targets.maximumTargets["critDMG_"])
        assertEquals(180.0, targets.maximumTargets["enerRech_"])
        assertEquals(100.0, targets.additionalCritRate)
        assertTrue("atk_" !in targets.additionalStats)
        assertEquals(-20.0, targets.additionalStats["enerRech_"])
        assertEquals(30.0, targets.additionalStats["critDMG_"])
        assertTrue("invalid" !in targets.additionalStats)
    }

    @Test
    fun `crit damage main stat beats crit substats when crit rate is already capped`() {
        val character = PlayerCharacterState(
            key = "TestCharacter",
            level = 90,
            constellation = 0,
            ascension = 6,
            normalTalent = 1,
            skillTalent = 1,
            burstTalent = 1,
        )
        val currentCirclet = artifact(
            slotKey = "circlet",
            mainStatKey = "critRate_",
            substats = listOf(
                PlayerArtifactStat("critDMG_", 21.0),
                PlayerArtifactStat("atk_", 11.7),
            ),
        ).copy(location = character.key)
        val critDamageCirclet = artifact(
            slotKey = "circlet",
            mainStatKey = "critDMG_",
            substats = listOf(
                PlayerArtifactStat("atk", 39.0),
                PlayerArtifactStat("def", 46.0),
            ),
        )
        val equipped = listOf(
            equippedArtifact("flower", "hp", emptyList()),
            equippedArtifact("plume", "atk", emptyList()),
            equippedArtifact("sands", "atk_", emptyList()),
            equippedArtifact("goblet", "pyro_dmg_", emptyList()),
            currentCirclet,
        )
        val snapshot = PlayerSnapshot(
            formatVersion = 3,
            source = "test",
            importedAt = Instant.EPOCH,
            characters = listOf(character),
            inventory = emptyMap(),
            inventoryNames = emptyMap(),
            exportedInventoryKeys = 0,
            artifacts = equipped + critDamageCirclet,
            weapons = emptyList(),
        )
        val targets = service.createTargets(
            profile = ArtifactOptimizationProfile.ATTACK,
            custom = true,
            requestedPriorityStats = listOf("critRate_", "critDMG_", "atk_"),
            requestedMaximumTargets = mapOf("critRate_" to 100.0),
        )

        val result = service.optimize(
            snapshot = snapshot,
            character = character,
            profile = ArtifactOptimizationProfile.ATTACK,
            targets = targets,
            setSelection = ArtifactSetSelection(ArtifactSetSelectionMode.NONE),
            baseStats = OptimizerBaseStats(
                characterStats = mapOf("critRate_" to 97.0, "critDMG_" to 50.0),
                weaponStats = emptyMap(),
                bonusStats = emptyMap(),
            ),
        )

        val suggestedCirclet = requireNotNull(result.loadout)
            .pieces.single { it.artifact.slotKey == "circlet" }
        assertEquals("critDMG_", suggestedCirclet.artifact.mainStatKey)
    }

    @Test
    fun `allowed main stats establish suitability without a quality bonus`() {
        val targets = service.createTargets(
            profile = ArtifactOptimizationProfile.ATTACK,
            custom = true,
            requestedMainStats = mapOf("circlet" to "critRate_|critDMG_"),
            requestedPriorityStats = listOf("critRate_", "critDMG_", "eleMas"),
            requestedMaximumTargets = mapOf("critRate_" to 100.0),
        )
        val critCirclet = artifact(
            slotKey = "circlet",
            mainStatKey = "critRate_",
            substats = listOf(
                PlayerArtifactStat("hp", 299.0),
                PlayerArtifactStat("def", 23.0),
            ),
        )
        val hpCirclet = artifact(
            slotKey = "circlet",
            mainStatKey = "hp_",
            substats = listOf(
                PlayerArtifactStat("critRate_", 11.7),
                PlayerArtifactStat("critDMG_", 23.3),
                PlayerArtifactStat("eleMas", 46.6),
                PlayerArtifactStat("atk_", 11.7),
            ),
        )

        val critScore = service.evaluate(
            critCirclet,
            ArtifactOptimizationProfile.ATTACK,
            targets,
            contextTotals = mapOf("critRate_" to 55.0, "critDMG_" to 140.0),
        )
        val hpScore = service.evaluate(
            hpCirclet,
            ArtifactOptimizationProfile.ATTACK,
            targets,
            contextTotals = mapOf("critRate_" to 55.0, "critDMG_" to 140.0),
        )

        assertEquals(0.0, critScore.score)
        assertTrue(hpScore.score > critScore.score)
        assertTrue(critScore.mainStatFit > hpScore.mainStatFit)
    }

    @Test
    fun `main stats receive no constant quality bonus`() {
        val targets = service.createTargets(
            profile = ArtifactOptimizationProfile.ATTACK,
            custom = true,
            requestedPriorityStats = listOf("critRate_", "critDMG_"),
            requestedMaximumTargets = mapOf("critRate_" to 100.0),
        )
        val critRateCirclet = artifact(
            slotKey = "circlet",
            mainStatKey = "critRate_",
            substats = emptyList(),
        )
        val critDamageCirclet = artifact(
            slotKey = "circlet",
            mainStatKey = "critDMG_",
            substats = emptyList(),
        )

        val critRateScore = service.evaluate(
            critRateCirclet,
            ArtifactOptimizationProfile.ATTACK,
            targets,
            contextTotals = mapOf("critRate_" to 82.0, "critDMG_" to 130.0),
        )
        val critDamageScore = service.evaluate(
            critDamageCirclet,
            ArtifactOptimizationProfile.ATTACK,
            targets,
            contextTotals = mapOf("critRate_" to 82.0, "critDMG_" to 130.0),
        )

        assertEquals(0.0, critRateScore.score)
        assertEquals(0.0, critDamageScore.score)
    }

    @Test
    fun `build totals include basis weapon bonus main stats and substats`() {
        val character = PlayerCharacterState(
            key = "TestCharacter",
            level = 90,
            constellation = 0,
            ascension = 6,
            normalTalent = 1,
            skillTalent = 1,
            burstTalent = 1,
        )
        val artifacts = listOf(
            equippedArtifact("flower", "hp", listOf(PlayerArtifactStat("critRate_", 3.0))),
            equippedArtifact("plume", "atk", listOf(PlayerArtifactStat("critDMG_", 6.0))),
            equippedArtifact("sands", "enerRech_", emptyList()),
            equippedArtifact("goblet", "pyro_dmg_", emptyList()),
            equippedArtifact("circlet", "critRate_", emptyList()),
        )
        val snapshot = PlayerSnapshot(
            formatVersion = 3,
            source = "test",
            importedAt = Instant.EPOCH,
            characters = listOf(character),
            inventory = emptyMap(),
            inventoryNames = emptyMap(),
            exportedInventoryKeys = 0,
            artifacts = artifacts,
            weapons = emptyList(),
        )
        val targets = service.createTargets(
            profile = ArtifactOptimizationProfile.ATTACK,
            custom = true,
            requestedPriorityStats = listOf("critRate_", "critDMG_", "enerRech_"),
            requestedMinimumTargets = mapOf("critRate_" to 50.0, "enerRech_" to 150.0),
        )
        val baseStats = OptimizerBaseStats(
            characterStats = mapOf(
                "critRate_" to 10.0,
                "critDMG_" to 80.0,
                "enerRech_" to 100.0,
            ),
            weaponStats = mapOf("critRate_" to 5.0),
            bonusStats = mapOf("critRate_" to 5.0),
        )

        val result = service.optimize(
            snapshot = snapshot,
            character = character,
            profile = ArtifactOptimizationProfile.ATTACK,
            targets = targets,
            setSelection = ArtifactSetSelection(ArtifactSetSelectionMode.NONE),
            baseStats = baseStats,
        )

        assertEquals(54.1, result.currentBuildStats.totalValues.getValue("critRate_"), 0.05)
        assertEquals(86.0, result.currentBuildStats.totalValues.getValue("critDMG_"), 0.05)
        assertEquals(151.8, result.currentBuildStats.totalValues.getValue("enerRech_"), 0.05)
        assertTrue(result.currentBuildStats.allMinimumTargetsMet)
    }

    @Test
    fun `analyzes farming leveling and set preserving loadouts from a GOOD snapshot`() {
        val snapshot = importService.parse(
            Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT),
        )
        val character = snapshot.characters.first { state ->
            snapshot.artifacts.count {
                GoodKeyNormalizer.normalize(it.location.orEmpty()) ==
                    GoodKeyNormalizer.normalize(state.key)
            } == 5
        }
        val equipped = snapshot.artifacts.filter {
            GoodKeyNormalizer.normalize(it.location.orEmpty()) ==
                GoodKeyNormalizer.normalize(character.key)
        }
        val profile = service.inferProfile(equipped)

        val result = service.optimize(snapshot, character, profile)

        assertEquals(5, result.currentPieces.size)
        assertTrue(result.currentPieces.all { it.mainStatFormattedValue.isNotBlank() })
        assertTrue(result.currentAverageScore in 0.0..100.0)
        assertTrue(result.farmingOutlooks.isNotEmpty())
        result.farmingOutlooks.forEach { outlook ->
            assertTrue(outlook.chancePerOnSetDrop in 0.0..1.0)
            assertTrue(outlook.chancePerDomainFiveStar in 0.0..1.0)
            assertTrue(outlook.chanceAfterTwentyDomainDrops in 0.0..1.0)
        }
        assertEquals(result.currentPieces.size, result.artifactOutlooks.size)
        assertEquals(result.currentPieces.size, result.levelingRecommendations.size)
        assertTrue(result.levelingCandidates.size <= 15)
        result.levelingCandidates.forEach {
            assertTrue(it.chanceToImprove in 0.0..1.0)
            assertTrue(it.chanceToImprove >= 0.30)
            assertTrue(it.chanceNextUpgradeUseful in 0.0..1.0)
            assertTrue(it.chanceAllUpgradesUseful in 0.0..1.0)
            assertTrue(it.expectedUsefulUpgrades in 0.0..it.remainingUpgrades.toDouble())
            assertTrue(it.likelyLowScore <= it.likelyHighScore)
            assertTrue(it.piece.artifact.level < 20)
        }
        result.levelingRecommendations.forEach { recommendation ->
            recommendation.candidates.forEach { candidate ->
                assertEquals(
                    recommendation.currentPiece.artifact.slotKey,
                    candidate.piece.artifact.slotKey,
                )
            }
        }

        result.loadout?.let { suggestion ->
            val counts = suggestion.pieces.groupingBy {
                GoodKeyNormalizer.normalize(it.artifact.setKey)
            }.eachCount()
            result.setPlan.requirements.forEach { requirement ->
                assertTrue((counts[requirement.setKey] ?: 0) >= requirement.count)
            }
            assertTrue(suggestion.pieces.all { it.suitable })
        }
    }

    @Test
    fun `perfect useful rolls reach one hundred and the worked example scores eighty three`() {
        val perfect = artifact(
            slotKey = "flower", mainStatKey = "hp",
            substats = listOf(
                PlayerArtifactStat("critRate_", 6 * 3.89),
                PlayerArtifactStat("critDMG_", 7.77),
                PlayerArtifactStat("atk_", 5.83),
                PlayerArtifactStat("enerRech_", 6.48),
            ),
        )
        val example = perfect.copy(substats = listOf(
            PlayerArtifactStat("critRate_", 10.1),
            PlayerArtifactStat("critDMG_", 20.2),
            PlayerArtifactStat("atk_", 9.3),
            PlayerArtifactStat("enerRech_", 5.2),
        ))
        assertEquals(100.0, service.evaluate(perfect, ArtifactOptimizationProfile.ATTACK).score, 0.001)
        assertEquals(8.3, service.evaluate(perfect, ArtifactOptimizationProfile.ATTACK).bestPossibleRolls, 0.001)
        assertEquals(82.8, service.evaluate(example, ArtifactOptimizationProfile.ATTACK).score, 0.1)
    }

    @Test
    fun `single wanted stat uses six attainable rolls and empty budgets remain finite`() {
        val targets = service.createTargets(ArtifactOptimizationProfile.REACTION, true,
            requestedPriorityStats = listOf("eleMas"))
        val piece = artifact(slotKey = "flower", mainStatKey = "hp",
            substats = listOf(PlayerArtifactStat("eleMas", 6 * 23.31)))
        val evaluation = service.evaluate(piece, ArtifactOptimizationProfile.REACTION, targets)
        assertEquals(6.0, evaluation.bestPossibleRolls)
        assertEquals(100.0, evaluation.score, 0.001)
        val emMain = piece.copy(slotKey = "sands", mainStatKey = "eleMas", substats = emptyList())
        val noBudget = service.evaluate(emMain, ArtifactOptimizationProfile.REACTION, targets)
        assertEquals(0.0, noBudget.bestPossibleRolls)
        assertEquals(0.0, noBudget.score)
    }

    @Test
    fun `three line starters and unfinished pieces share a fixed final quality budget`() {
        val targets = service.createTargets(ArtifactOptimizationProfile.REACTION, true,
            requestedPriorityStats = listOf("eleMas"))
        val threeLine = artifact(slotKey = "flower", mainStatKey = "hp",
            substats = listOf(
                PlayerArtifactStat("eleMas", 5 * 23.31),
                PlayerArtifactStat("def", 23.15),
                PlayerArtifactStat("atk", 19.45),
                PlayerArtifactStat("critDMG_", 7.77),
            )).copy(totalRolls = 8)
        assertEquals(100.0 * 5 / 6, service.evaluate(threeLine, ArtifactOptimizationProfile.REACTION, targets).score, 0.001)
        val starter = threeLine.copy(level = 0, totalRolls = 3,
            substats = threeLine.substats.take(3).map {
                if (it.key == "eleMas") it.copy(value = 23.31) else it
            })
        assertEquals(100.0 / 6, service.evaluate(starter, ArtifactOptimizationProfile.REACTION, targets).score, 0.001)
    }

    @Test
    fun `total stat requirements and set membership do not change individual quality`() {
        val piece = artifact(substats = listOf(PlayerArtifactStat("enerRech_", 12.96)))
        val base = service.createTargets(ArtifactOptimizationProfile.ENERGY_SUPPORT, true,
            requestedPriorityStats = listOf("enerRech_", "hp_"))
        val constrained = base.copy(minimumTargets = mapOf("enerRech_" to 180.0),
            maximumTargets = mapOf("enerRech_" to 200.0))
        val original = service.evaluate(piece, ArtifactOptimizationProfile.ENERGY_SUPPORT, base)
        val changed = service.evaluate(piece.copy(setKey = "HeartOfDepth"),
            ArtifactOptimizationProfile.ENERGY_SUPPORT, constrained,
            contextTotals = mapOf("enerRech_" to 250.0))
        assertEquals(original.score, changed.score)
        assertEquals(original.weightedRolls, changed.weightedRolls)
    }

    @Test
    fun `main stat alternatives are sanitized and each accepted alternative is suitable`() {
        val targets = service.createTargets(ArtifactOptimizationProfile.REACTION, true,
            requestedMainStats = mapOf("circlet" to "critRate_|critDMG_|eleMas|invalid|critRate_"))
        assertEquals("critRate_|critDMG_|eleMas", targets.mainStats["circlet"])
        for (key in listOf("critRate_", "critDMG_", "eleMas")) {
            assertEquals(1.0, service.evaluate(artifact(slotKey = "circlet", mainStatKey = key,
                substats = emptyList()), ArtifactOptimizationProfile.REACTION, targets).mainStatFit)
        }
        assertEquals(0.0, service.evaluate(artifact(slotKey = "circlet", mainStatKey = "hp_",
            substats = emptyList()), ArtifactOptimizationProfile.REACTION, targets).mainStatFit)
    }

    @Test
    fun `unfinished equipped artifacts show reproducible projected quality and set fit`() {
        val character = PlayerCharacterState("TestCharacter", 90, 0, 6, 1, 1, 1)
        val starter = equippedArtifact("flower", "hp", listOf(
            PlayerArtifactStat("critRate_", 3.89),
            PlayerArtifactStat("critDMG_", 7.77),
            PlayerArtifactStat("atk_", 5.83),
        )).copy(level = 0, totalRolls = 3)
        val offPiece = equippedArtifact("plume", "atk", emptyList()).copy(setKey = "HeartOfDepth")
        val snapshot = PlayerSnapshot(3, "test", Instant.EPOCH, characters = listOf(character),
            inventory = emptyMap(), inventoryNames = emptyMap(), exportedInventoryKeys = 0,
            artifacts = listOf(starter, offPiece), weapons = emptyList())
        val selection = ArtifactSetSelection(ArtifactSetSelectionMode.CUSTOM,
            listOf(ArtifactSetTarget("gladiatorsfinale", 4)))
        val first = service.optimize(snapshot, character, ArtifactOptimizationProfile.ATTACK, setSelection = selection)
        val second = service.optimize(snapshot, character, ArtifactOptimizationProfile.ATTACK,
            targets = ArtifactOptimizationTargets.defaults(ArtifactOptimizationProfile.ATTACK)
                .copy(additionalStats = mapOf("critRate_" to 100.0)), setSelection = selection)
        val flower = first.currentPieces.first()
        val projection = requireNotNull(flower.qualityProjection)
        assertTrue(projection.averageFinalScore > flower.score)
        assertTrue(projection.likelyLowScore <= projection.likelyHighScore)
        assertTrue(projection.likelyHighScore <= 100.0)
        assertEquals(projection, second.currentPieces.first().qualityProjection)
        assertEquals(flower.score, second.currentPieces.first().score)
        assertEquals("optimizer.setFit.target", flower.setFitKey)
        assertEquals("optimizer.setFit.offPiece", first.currentPieces.last().setFitKey)
        assertEquals(null, first.currentPieces.last().qualityProjection)
    }

    @Test
    fun `loadout accepts a tiny loss in the highest priority for a substantial useful gain`() {
        val character = PlayerCharacterState("TestCharacter", 90, 0, 6, 1, 1, 1)
        val flower = equippedArtifact("flower", "hp", listOf(
            PlayerArtifactStat("enerRech_", 6.49), PlayerArtifactStat("critDMG_", 7.77)))
        val replacement = flower.copy(location = null, substats = listOf(
            PlayerArtifactStat("enerRech_", 6.48), PlayerArtifactStat("critDMG_", 31.08)))
        val equipped = listOf(flower,
            equippedArtifact("plume", "atk", emptyList()),
            equippedArtifact("sands", "atk_", emptyList()),
            equippedArtifact("goblet", "pyro_dmg_", emptyList()),
            equippedArtifact("circlet", "critRate_", emptyList()))
        val snapshot = PlayerSnapshot(3, "test", Instant.EPOCH, characters = listOf(character),
            inventory = emptyMap(), inventoryNames = emptyMap(), exportedInventoryKeys = 0,
            artifacts = equipped + replacement, weapons = emptyList())
        val targets = service.createTargets(ArtifactOptimizationProfile.ATTACK, true,
            requestedPriorityStats = listOf("enerRech_", "critDMG_"))
        val result = service.optimize(snapshot, character, ArtifactOptimizationProfile.ATTACK, targets)
        val loadout = requireNotNull(result.loadout)
        assertEquals(replacement, loadout.pieces.first { it.artifact.slotKey == "flower" }.artifact)
    }

    @Test
    fun `slots with no useful substat budget do not reduce an EM only builds quality`() {
        val character = PlayerCharacterState("TestCharacter", 90, 0, 6, 1, 1, 1)
        val pieces = listOf(
            equippedArtifact("flower", "hp", listOf(PlayerArtifactStat("eleMas", 6 * 23.31))),
            equippedArtifact("plume", "atk", listOf(PlayerArtifactStat("eleMas", 6 * 23.31))),
        ) + listOf("sands", "goblet", "circlet").map {
            equippedArtifact(it, "eleMas", emptyList())
        }
        val snapshot = PlayerSnapshot(3, "test", Instant.EPOCH, characters = listOf(character),
            inventory = emptyMap(), inventoryNames = emptyMap(), exportedInventoryKeys = 0,
            artifacts = pieces, weapons = emptyList())
        val targets = service.createTargets(ArtifactOptimizationProfile.REACTION, true,
            requestedPriorityStats = listOf("eleMas"))
        val result = service.optimize(snapshot, character, ArtifactOptimizationProfile.REACTION, targets)
        assertEquals(100.0, result.currentAverageScore, 0.001)
        assertEquals(3, result.currentPieces.count { it.bestPossibleRolls == 0.0 })
    }

    private fun artifact(
        slotKey: String = "goblet",
        mainStatKey: String = "pyro_dmg_",
        substats: List<PlayerArtifactStat>,
    ): PlayerArtifact = PlayerArtifact(
        setKey = "GladiatorsFinale",
        slotKey = slotKey,
        level = 20,
        rarity = 5,
        mainStatKey = mainStatKey,
        location = null,
        locked = false,
        substats = substats,
        totalRolls = 9,
        astralMark = false,
        elixirCrafted = false,
    )

    private fun equippedArtifact(
        slotKey: String,
        mainStatKey: String,
        substats: List<PlayerArtifactStat>,
    ): PlayerArtifact = artifact(
        slotKey = slotKey,
        mainStatKey = mainStatKey,
        substats = substats,
    ).copy(location = "TestCharacter")
}
