# Weapon data pipeline

Weapon catalogue and detail data now has one remote source: the official HoYoWiki API.

## Build-time snapshot

`generateBundledCatalogSnapshot` calls `BundledCatalogSnapshotKt`. It requests the HoYoWiki weapon list and every referenced weapon page, then writes `data/bundled-catalog-snapshot.json` into the generated resources directory. The snapshot contains JSON and remote image URLs only; image bytes are not bundled.

Both `bootJar` and `desktopBootJar` depend on this task. A packaged build therefore contains an offline first-start catalogue captured at build time.

## Application startup

`BundledCatalogSnapshotImporter` runs before live synchronization. On every startup it applies the bundled HoYoWiki snapshot idempotently:

1. material entries establish canonical material names and image URLs;
2. weapon list entries establish identity, rarity, type, secondary stat, description, icon URL, and HoYoWiki page ID;
3. full weapon pages add source/region/version, passive and story text, ascension boundaries, material costs, and ascended/unascended gallery images;
4. all values are saved through `WeaponCatalogStore` into typed weapon, image, progression, and material-cost entities.

The importer is intentionally not guarded by a one-time marker. Parser changes and newly bundled source values are reflected the next time the application starts.

After the bundled import, `HoyolabCatalogStartupSynchronizer` performs one live catalogue-list refresh per application process. If that request fails, persisted data and the bundled snapshot remain usable.

## On-demand detail refresh

`HoyolabWeaponGalleryService.enrich` is called when a weapon detail page is opened. An entry whose parser data version is current is returned directly. Otherwise the service fetches its known HoYoWiki page ID (with official search as a fallback), parses it, and persists the result. Failed lookups have a short cooldown.

## Frontend preparation

`WeaponCatalogService` provides the catalogue grid and stable local media routes. `WeaponDataService` is a small typed lookup facade over `WeaponCatalogStore`; it has no remote client or JSON fallback. `WeaponController` combines the definition with owned copies and planning data for `weapon.html`.

Images are downloaded only when a `/media/weapons/{key}/{type}` route is requested. `ImageUrlRegistry` keeps generated defaults separate from administrator overrides. The three use-case keys are `ICON`, `FULL_ASCENDED`, and `FULL_UNASCENDED`; they do not encode the source name.

## Combat stat resolution

HoYoWiki exposes weapon stats at ascension boundaries. `EquipmentStatCatalog` uses the requested ascension phase and linearly interpolates between that phase's displayed start/end boundary values. Both sides of a boundary remain distinct: for example level 80 before ascension and level 80 after ascension use different base-ATK values.

## Main interfaces

| Responsibility | Interface/service |
| --- | --- |
| HoYoWiki HTTP boundary | `HoyolabWikiDataSource` |
| List-to-domain mapping | `HoyolabCatalogEntryMapper` |
| Shared page primitives | `HoyolabPageParser` |
| Weapon detail parsing | `HoyolabWeaponGalleryService` |
| Typed persistence | `WeaponCatalogStore` |
| Catalogue/UI metadata | `WeaponCatalogService` |
| Detail lookup facade | `WeaponDataService` |

