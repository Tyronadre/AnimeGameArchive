# Irminsul capture helper

This is the privileged, headless capture component used by Genshin Archive's
desktop mode. It is adapted from
[konkers/irminsul](https://github.com/konkers/irminsul) version 0.1.19,
commit `f8b027c2523e300209f39e3968f386cea11eb61d`.

The following files are retained from upstream with only integration-specific
changes around them:

- `src/admin.rs`
- `src/capture.rs`
- `src/good.rs`
- `src/player_data.rs`
- `keys/gi.json`

Irminsul's MIT license is preserved in `LICENSE`. The helper has no independent
UI. It captures one complete character and inventory snapshot, exports GOOD v3,
and sends it to the token-protected loopback endpoint supplied by the desktop
application.
