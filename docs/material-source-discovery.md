# Automatic material source discovery

The background HoYoWiki refresh checks the enemy catalogue in addition to the
material, character, weapon, and artifact catalogues. Enemy detail pages supply
source types and explicit reward page references. The importer never infers a
world boss from the broad enemy-list filter or from a character's ascension costs.

## Source evidence

- `baseInfo.Type` distinguishes bosses, weekly bosses, common/elite enemies, and
  local legends. Unknown types retain raw data and an extraction diagnostic.
- `drop_material.list` contains reward wiki-page IDs. Resolve these through the
  English catalogue or fetched page, not the embedded nickname (which may use a
  different language).
- Only progression rewards and elemental gems become planning memberships. Mora,
  billets, solvents, artifacts, and other rewards stay in raw detail JSON and do
  not trigger exclusive boss-collection detection.
- Talent book `baseInfo.Source` supplies a challenge name and weekday rotation.
  Existing physical-domain mappings remain available. New families appear under
  the published challenge name, explicitly labeled as a talent challenge.

Real game IDs still come from the material identity catalogue. Unknown mappings
use the existing generated IDs; identity repair preserves source relationships
when a real ID becomes available.

## Persistence and refresh

`material_source` stores source provenance and optional enemy wiki identity;
`material_source_material` stores planning memberships and talent schedules.
`material.category_origin` protects verified classifications from seed/usage
inference. `hoyowiki_entry` stores raw JSON plus extraction version, last success,
and last error separately from catalogue-list checkpoints.

Each successfully resolved page is projected in a transaction with its extraction
checkpoint. Failed fetches, malformed references, and conflicting classifications
keep previous links. Sources are not deleted merely because they disappear from
a list. Failed and new pages retry on startup; successful details refresh after
the configured full-refresh interval (seven days by default). Parser-version
changes replay cached JSON first. A run uses at most four concurrent workers and
deduplicates referenced-page requests.

Enemy variants remain separate database sources but share a website card when
their progression-drop sets match. Explicit talent schedules work with generated
IDs and take precedence over game-ID rotation arithmetic.

## Offline bundle and limits

The generated format-v3 catalogue bundle includes enemy summaries/details and
source-material details. Bundled and live pages use the same projection code.
Bundled imports do not make HTTP requests; successful saved data survives offline
restarts. Bundled source lists remain fallback data.

HoYoWiki's checked domain pages are incomplete, so physical-domain discovery is
not automated. Reward quantities/drop rates and generalized crafting recipes are
not inferred. Missing/unrecognized wiki data stays unresolved. Per-run logs report
updated sources, unresolved rewards/schedules, skipped types, and failed pages.

Test fixtures in `src/test/resources/hoyowiki-sources/` are minimal public API
responses captured on 2026-10-26, limited to identity, filters, base information,
and loot components. Tests run against these fixtures without network access.
