# Genshin Archive

Genshin Archive can run in two modes that share the same Spring backend and
Thymeleaf interface.

## Static game data

The official HoYoWiki is the catalogue and detail source for characters,
weapons, artifacts, and materials. Packaging creates a JSON snapshot containing
the current HoYoWiki records (but not image bytes). The snapshot is projected
into typed database tables once per packaged snapshot. After startup, four concurrent
one-entry HoYoWiki requests compare each category's total and first page ID with the
last successful synchronization. Only changed categories are downloaded in full.
An unchanged category is also refreshed after seven days to catch edits that do not
change its count; failed or incomplete downloads leave its previous checkpoint intact.
Persisted data remains available if the live request fails.

`hoyowiki_entry` stores wiki page IDs, catalog summaries, fetched page details, and
the last live query times. Characters, weapons, materials, and persisted artifact
sets link to those records through `hoyowiki_entry_id`; wiki IDs remain separate
from game item IDs. Bundled imports do not count as live queries. Artifact summaries
are restored from the database when the startup check skips their download.

Material wiki-to-game ID mappings are also restored from persisted links, so
character and weapon detail imports do not depend on a full catalog download.
Before the catalog caches load, startup repairs verified legacy/synthetic material
aliases and their cost/source links. Conflicting cost amounts are retained and logged.

HoYoWiki exposes page ids for materials, while GOOD inventories and source groups use
in-game item ids. [`material-identities.json`](src/main/resources/data/material-identities.json)
is the versioned bridge between both namespaces. It is bundled for offline/fresh installs and
the live catalogue refresh checks the configured raw GitHub URL for a newer copy, validates it,
and caches it before importing HoYoWiki data. New material mappings can therefore be shipped as
a data-file update without changing the application binary. Increment `catalogVersion` whenever
the file changes; the app only activates cached or remote catalogs newer than its current one.

The import is controlled with these properties:

```properties
genshin.content.hoyolab-wiki-enabled=true
genshin.content.hoyolab-catalog-sync-enabled=true
genshin.content.hoyolab-catalog-full-refresh-interval=7d
genshin.content.rerun-character-imports=false
genshin.content.material-identity-catalog-url=https://raw.githubusercontent.com/Tyronadre/AnimeGameArchive/master/src/main/resources/data/material-identities.json
```

## Web mode

Character detail pages are imported once per character, with completion saved in the database.
Before calculating a material plan, missing cost details are fetched for the included
characters (including the selected Traveler element), with at most four imports in parallel.
Saved costs are reused on later calculations. If a fetch fails, the materials page identifies
the affected characters and labels the totals as incomplete.
For development, rerun them at startup with:

```powershell
.\gradlew.bat bootRun --args="--genshin.content.rerun-character-imports=true"
```

The option reapplies bundled character pages and fetches remaining saved characters when
`hoyolab-wiki-enabled` is true. Each character is rerun at most once during that startup.
Without the option, existing character stats are preserved across restarts and importer version changes.

The normal web application keeps account authentication and the configured
MySQL database:

```powershell
.\gradlew.bat bootRun
```

## Desktop mode

Desktop mode runs the backend only on `127.0.0.1`, signs in a dedicated local
user automatically, and stores its file-backed H2 database and cache below
`%USERPROFILE%\.genshinapp\desktop`. A lightweight native launcher is shown
immediately while the local database and Spring services initialize. Use its
button to open the full interface in the default browser.

Set `GENSHIN_DESKTOP_HOME` before launch to use another local data directory.

H2 runs in automatic mixed mode, so an external H2 client (for example, IntelliJ's
database tool window or DBeaver) can inspect the same database while the app runs.
No Docker container or separately installed database server is required. The
automatic server binds to `127.0.0.1` and chooses an available port.

Use the H2 JDBC driver matching the app's version (currently 2.3.232), username
`sa`, and an empty password. For the default data directory, use this JDBC URL
(replace `<username>` with your Windows user name):

```text
jdbc:h2:file:C:/Users/<username>/.genshinapp/desktop/database/genshinapp;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE;IFEXISTS=TRUE
```

Use the same absolute database path in both processes. `IFEXISTS=TRUE` prevents a
client with an incorrect path from silently creating a different database.
Restart the app after upgrading from embedded-only mode before connecting the
external client.

To start fresh while keeping the old database and cache intact, use a new data
directory for the next launch:

```powershell
$env:GENSHIN_DESKTOP_HOME = "$env:USERPROFILE/.genshinapp/desktop-fresh"
.\gradlew.bat desktopRun
```

For development:

```powershell
.\gradlew.bat desktopRun
```

The native local-tools panel is reserved for machine-only features such as
game-data capture. Its **Start capture** action launches the bundled,
Irminsul-derived Rust helper with a Windows administrator prompt. Start capture
before entering Genshin Impact; once character and inventory packets are found,
the resulting GOOD v3 snapshot is imported automatically and the helper stays
connected. Complete inventory item changes are then merged and saved locally
after a short debounce, covering artifact, weapon, and material updates and
deletions until **Stop capture** is selected. Character progression and equipped
item location changes still require a fresh capture. The system-tray icon remains
available as a secondary way to reopen or stop the application. The dashboard
keeps the ten latest detected inventory changes and refreshes that feed while it
is open. Packet heartbeats, recognized changes, and snapshot saves are shown in
the launcher's live log and persisted at
`%USERPROFILE%\.genshinapp\desktop\irminsul\live-capture.log`. For exploratory
capture work, the helper also writes decoded game-command inspection entries as
JSON Lines to
`%USERPROFILE%\.genshinapp\desktop\irminsul\packet-inspection.jsonl`. That file
is local-only but should be treated as private account/game data.

To create a Windows application image containing both the application and its
Java runtime:

```powershell
.\gradlew.bat packageDesktop
```

The runnable image is written to:

```text
build\desktop\Genshin Archive\Genshin Archive.exe
```

To create the smaller per-user Windows installer:

```powershell
.\gradlew.bat packageDesktopInstaller
```

The online installer contains the application and reuses the private Java 21
runtime from an existing installation when available, so upgrades do not
download Java again. On a fresh installation, it downloads the latest Eclipse
Temurin Java 21 JRE, verifies its SHA-256 checksum, and installs it privately
beside the application without modifying the system `PATH` or requiring
administrator access. The installer is written to `build\desktop-installer`.

For an installer that also contains Java and works without internet access, use:

```powershell
.\gradlew.bat packageDesktopOfflineInstaller
```

The `desktop` Spring profile is the boundary for future local-only features,
including the elevated Irminsul capture helper. Normal web deployments never
activate that profile.
