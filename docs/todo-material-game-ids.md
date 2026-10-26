# Material IDs: retain generated fallbacks and identity resolution

## Revised decision

The earlier removal proposal is withdrawn. Missing game-ID mappings can occur in
current imports, even with a fresh development database. Generated IDs and their
later resolution are therefore ongoing import behavior, not solely compatibility
with old development databases.

Keep the generated material-ID fallback (`1,500,000,000 + HoYoWiki page ID`) so
materials, progression requirements, and farming sources can still be stored when
the actual game ID is unknown. Keep the synthetic-to-real-ID resolution in
`MaterialIdentityRepair` and its startup dependencies.

## Identity distinction

The current `Material.gameId` field can contain either an actual game ID or a
temporary generated ID. A generated ID is not an actual Genshin game ID, despite
the field name. Internal database row IDs and HoYoWiki page IDs remain separate
identities. Do not introduce raw wiki page IDs as game IDs.

When the actual game ID becomes available, resolve the temporary record to the
canonical material, preserving its requirement and farming-source references.
Keep the existing conflict checks rather than silently choosing between
inconsistent upgrade quantities.

## Follow-up TODO

- [ ] Clearly document or represent the distinction between actual game IDs and
  generated material identities for callers of `Material.gameId`.
- [ ] Verify that missing mappings still preserve material requirements through
  generated IDs and that later resolution preserves their references and amounts.
- [ ] If removing legacy raw-wiki-ID compatibility separately, retain generated-ID
  creation and synthetic-to-real-ID resolution. Do not remove the whole repair
  service merely because the project is in development.

This document records the revised plan; application behavior is unchanged.
