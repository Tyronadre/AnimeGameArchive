# Stored team builds (V2)

The stored-team panel remains a read-only view of saved progress and equipped items. It does not consume live calculator sliders, enabled effects, manual bonuses, or optimizer defaults.

Character stats are resolved exclusively by `CharacterStatCatalog` from the persisted nonzero base values, level growth and cumulative ascension bonuses. Intermediate levels follow the matching game growth curve calibrated to HoYoWiki's rounded checkpoints; secondary stats remain constant within each phase. Weapon stats still use their HoYoWiki boundary rows. Both sides of an ascension boundary remain distinct. Missing source rows are reported as unavailable instead of being guessed from rarity. See [character stat storage](character-stat-storage.md).

Artifact main-stat values use the compact `data/artifact-main-stats.json` gameplay table. Owned weapon and artifact values continue to come from `PlayerSnapshotStore`; this reference-data change never mutates owned equipment.

Unbuffed ATK is `(character ATK + weapon ATK) × (1 + ATK% / 100) + flat artifact ATK`. HP and DEF follow the same pattern. Other stats add their character, weapon, and artifact contributions. Artifact set effects, weapon passives, character passives, constellation effects, resonance, reactions, and temporary buffs are deliberately excluded from this stored-build total.
