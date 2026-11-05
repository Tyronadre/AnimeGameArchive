# Irminsul capture helper

This is the privileged, headless capture component used by Genshin Archive's
desktop mode. It is adapted from
[konkers/irminsul](https://github.com/konkers/irminsul) version 0.2.2,
commit `0765e6eb527b3cec5b1b8878d8f5d743f3afc156`.

The following files are retained from upstream with only integration-specific
changes around them:

- `src/admin.rs`
- `src/capture.rs`
- `src/good.rs`
- `src/player_data.rs`
- `keys/gi.json`

Irminsul's MIT license is preserved in `LICENSE`. The helper has no independent
UI or self-updater. It captures the complete login character and inventory snapshot, exports
GOOD v3, and sends it to the token-protected loopback endpoint supplied by the
desktop application. It then remains attached and recognizes complete inventory
item changes and deletions by their nested protobuf shape rather than
version-specific command IDs or outer field numbers. Changes are merged by item
GUID and exported after a 750 ms quiet period. When `--log-file` is supplied,
the helper records ten-second traffic heartbeats, recognized changes, and every
snapshot save so live-capture failures can be diagnosed without console access.
When `--packet-log-file` is supplied, it additionally records each decoded game
command as a JSON Lines inspection entry with command ID, payload size,
best-known category, and a bounded generic protobuf field tree. The packet log is
intended for local reverse-engineering decisions and may contain private
account/game data. Pktmon remains the default capture backend on Windows; builds
with the optional `pcap` feature can select pcap with `--capture-backend pcap`.

The vendored decoder includes the 7.2 character/inventory command IDs and outer
field numbers from [auto-artifactarium PR #3](https://github.com/konkers/auto-artifactarium/pull/3).
This is a local port of an unmerged patch; the recorded upstream main revision
has not changed. It does not fix the separate session-key discovery failure seen
when reconnecting from an already-running game without the helper's retained
client timestamp. A full game-process restart resolved that failure in the
November 5 verification; the updated helper imported both the inventory and
characters, saved the initial snapshot, and subsequently saved live changes.

With packet inspection enabled, failed session-key discovery also writes a
`key_discovery_failure` event containing one encrypted command (at most 64 KiB),
the handshake timestamp, retained client timestamp, and candidate server seeds.
Seeds are decimal strings to preserve 64-bit precision. The sample is flushed
immediately and collected only once per handshake, subject to the existing log
size limit. It allows offline investigation of key generation without another
live login for each hypothesis. Treat it as private session data, like the rest
of the inspection log. Existing logs contain only decoded commands and cannot
reconstruct this sample retroactively.

Live updates currently cover artifacts, weapons, and materials. Character
progression and equipped item locations are refreshed by starting a new capture.

For a fresh capture, fully exit the Genshin Impact process, start capture, then
launch the game and enter the world. Returning to the title screen is not a full
restart: the client can retain a random generator initialized before this helper
started. Session-key discovery searches near the login timestamp and can fail
when it has no retained timestamp from that older process. Development session
reuse below is intended for attaching to an existing session.

## Development session-key reuse

When the desktop launcher is run outside a packaged `jpackage` application, it passes a
development-only session-cache path to the helper. After the login handshake discovers the current
session key, the helper stores it together with the GUID-indexed player state under the desktop
home's `irminsul` directory. The
launcher's **Reuse current Genshin session (dev)** button restores that key after an application
restart so Genshin does not need to be restarted. Packaged launches never pass either session-key
argument and never expose the reuse button. A cached key is valid only until Genshin reconnects or
starts a new session.
