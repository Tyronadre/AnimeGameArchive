# Character stat storage

Character identity, talents, material costs, and progression now come from HoYoWiki. Character icon, card, and banner defaults are derived from their stable Wikia filenames; administrator overrides remain separate.

`BundledCatalogSnapshotImporter` first applies the build-time list and full-page snapshot. `HoyolabCatalogStartupSynchronizer` then refreshes the official list once per process, and `HoyolabCharacterPageService` refreshes a stale full page when its character detail is opened.

HoYoWiki ascension rows are stored as `GameCharacterProgression` children of `GameCharacter`. Each row keeps the before/after HP, ATK, DEF, and ascension-stat values at levels 1, 20, 40, 50, 60, 70, 80, and 90. Character and talent material costs reference the shared `Material` entity.

`CharacterStatCatalog` resolves calculator values from those persisted HoYoWiki boundaries. It preserves the selected ascension side at a boundary and interpolates intermediate character levels within the selected phase. Universal starting values are CRIT Rate 5%, CRIT DMG 50%, Energy Recharge 100%, and Elemental Mastery 0; the HoYoWiki ascension value is added to the character's declared ascension stat.

Replacing the bundled snapshot or changing the parser is reflected on the next startup. There is no one-time installation marker and no separate character growth-curve database.
