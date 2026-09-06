# Stored team builds (V2)

The stored-team panel remains a read-only view of saved progress and equipped items. It does not consume live calculator sliders, enabled effects, manual bonuses, or optimizer defaults.

Character and weapon base stats are resolved from the HoYoWiki ascension boundary rows stored in the typed character and weapon tables. Intermediate levels are interpolated inside the selected ascension phase, while both sides of an ascension boundary remain distinct. Missing source rows are reported as unavailable instead of being guessed from rarity.

Artifact main-stat values use the compact `data/artifact-main-stats.json` gameplay table. Owned weapon and artifact values continue to come from `PlayerSnapshotStore`; this reference-data change never mutates owned equipment.

Unbuffed ATK is `(character ATK + weapon ATK) × (1 + ATK% / 100) + flat artifact ATK`. HP and DEF follow the same pattern. Other stats add their character, weapon, and artifact contributions. Artifact set effects, weapon passives, character passives, constellation effects, resonance, reactions, and temporary buffs are deliberately excluded from this stored-build total.
