# Character stat storage

Character identity, talents, material costs, and stat checkpoints come from HoYoWiki. `HoyolabCharacterPageService.importPage` parses the ascension component, normalizes whitespace in stat labels (including nonbreaking spaces), and uses the existing ascension-stat type.

The importer converts a complete level 1–90 table into three collections owned by `GameCharacter`:

- `GameCharacterBaseStat` stores each nonzero level-one **After Ascension** value and the applicable growth-curve key. A zero secondary base value creates no row. Universal CRIT Rate, CRIT DMG and Energy Recharge defaults are defined once in `CharacterStatCatalog`, rather than repeated in character records.
- `GameCharacterLevelStat` stores cumulative level-only HP, ATK and DEF growth at each checkpoint. It excludes the level-one base and all ascension increases.
- `GameCharacterAscensionStat` stores cumulative bonuses for phases 1–6. HP, ATK and DEF bonuses come from the before/after increase at the same level. The secondary bonus is the phase's after-ascension value minus its level-one value.

For example, Lauma's imported base EM is 200 and her phase-six EM bonus is 115, giving 315 EM. Characters with a zero level-one CRIT DMG bonus retain the universal 50% and receive their ascension bonus once.

`data/character-base-stat-corrections.json` supplies known base stats absent from HoYoWiki. Its Nefer entry supplies 100 base EM. The page importer and legacy migration fill missing values from this file; `CharacterBaseStatCorrectionImporter` also backfills existing records at startup without a page reimport. Existing nonzero values are preserved, and repeated startups do not add the correction again. Lauma needs no correction because her EM is already imported from HoYoWiki. Add future missing stats to this data file rather than adding character conditions to stat calculations.

All requests for stats at a level and ascension go through `CharacterStatCatalog.resolve(key, level, ascension)`:

```text
stat = base value + level growth + cumulative ascension bonus
```

The catalog validates both level and phase, so 80/5 and 80/6 remain distinct and 81/5 is unavailable. It returns null for missing data or invalid combinations. Traveler aliases share the canonical Traveler record.

The importer identifies the S4 or S5 growth family by fitting the observed level-only growth across HP, ATK and DEF. It does not assume a curve from rarity: the five-star Traveler uses S4. Between checkpoints, the catalog uses the selected game's curve coordinates to calculate the fraction of level growth. Secondary stats remain constant within a phase.

HoYoWiki publishes rounded values. Multiplying a rounded level-one ATK by an uncalibrated curve would amplify its rounding error. The level-growth checkpoints preserve every supplied boundary value while calibrating the game curve between them. Intermediate results therefore follow the game curve at **HoYoWiki's source precision**; they cannot recover hidden unrounded game coefficients. No linear interpolation by level or independent calculation in a profile/model remains.

The offline curve resource `src/main/resources/data/character-growth-curves.json` is copied from [Genshin Optimizer's character curve data](https://github.com/frzyc/genshin-optimizer/blob/e2686ab3a00e6ac7fc2a786a3585027a2d75ce63/libs/gi/stats/Data/Characters/expCurve.json), pinned to commit `e2686ab3a00e6ac7fc2a786a3585027a2d75ce63`. This is a shared numeric growth table, not a second character catalog.

On upgrade, `CharacterStatStorageMigration` converts complete legacy `game_character_progression` records that have no normalized base stats. The old table is no longer mapped or written; an existing copy is left untouched. Incomplete legacy records require a page refresh. Fresh databases only create the three new stat tables.

`BundledCatalogSnapshotImporter` imports packaged character pages only for characters that have never completed a page import. Completion is persisted through `hoyolabDataVersion > 0`; changing the importer version does not overwrite saved character stats. Live enrichment follows the same rule. Failed fetches may be retried; an imported page with incomplete stats preserves its previous usable profile and is still marked imported.

For development, start with `--genshin.content.rerun-character-imports=true`. Bundled character pages are reapplied once for that startup. The startup rerunner fetches remaining saved characters when live HoYoWiki access is enabled. A character already rerun from the snapshot is skipped by the live rerunner. Re-importing updates existing child rows and removes obsolete values without duplicates.
