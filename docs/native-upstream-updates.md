# Updating the native Rust sources

The installer build runs `checkNativeUpstreamUpdates` automatically. The task
checks the recorded upstream versions in `native/upstream-versions.properties`
and prints a warning when an update is available. A failed network check never
fails the installer build.

Run the check separately with:

```powershell
.\gradlew.bat checkNativeUpstreamUpdates
```

The native sources are locally adapted, so do not replace their directories
with an upstream checkout. Review and port upstream changes manually.

## Updating Irminsul

1. Read the release notes and note the version and commit printed by the Gradle
   warning.
2. Create a checkout used only for comparison:

   ```powershell
   git clone --no-checkout https://github.com/konkers/irminsul.git build/upstreams/irminsul
   ```

   If that directory already exists, refresh it instead:

   ```powershell
   git -C build/upstreams/irminsul fetch --tags origin
   ```

3. Compare the recorded revision with the new revision. Copy the full current
   revision from `native/upstream-versions.properties`, and the new revision
   from the warning's comparison URL:

   ```powershell
   git -C build/upstreams/irminsul diff <current-commit> <new-commit>
   ```

4. Port the relevant changes into `native/irminsul-helper`. Pay particular
   attention to the locally adapted files listed in
   `native/irminsul-helper/README.md`:

   - `src/admin.rs`
   - `src/capture.rs`
   - `src/good.rs`
   - `src/player_data.rs`
   - `keys/gi.json`

5. After the port is complete, update `irminsul.currentVersion` and
   `irminsul.currentRevision` in `native/upstream-versions.properties`.

## Updating auto-artifactarium

Irminsul used the `konkers/auto-artifactarium` fork at the revision recorded in
`native/upstream-versions.properties`. The local copy contains integration
changes and is a path dependency, so `cargo update` cannot update it.

1. Create or refresh a comparison checkout:

   ```powershell
   git clone --no-checkout https://github.com/konkers/auto-artifactarium.git build/upstreams/auto-artifactarium
   git -C build/upstreams/auto-artifactarium fetch origin main
   ```

2. Compare the recorded revision with the new revision. Copy the full values
   from `native/upstream-versions.properties` and the warning's comparison URL:

   ```powershell
   git -C build/upstreams/auto-artifactarium diff <current-commit> <new-commit>
   ```

3. Port the required changes into `native/vendor/auto-artifactarium` and update
   `autoArtifactarium.currentRevision` only after the port is complete.

## Verification

From the repository root, run:

```powershell
cargo fmt --check --manifest-path native/irminsul-helper/Cargo.toml
cargo test --locked --manifest-path native/irminsul-helper/Cargo.toml
cargo build --release --locked --manifest-path native/irminsul-helper/Cargo.toml
.\gradlew.bat test
.\gradlew.bat packageDesktopInstaller
```

Commit the native source changes, `Cargo.toml`, `Cargo.lock`, and
`native/upstream-versions.properties` together. The final installer command will
run the update check again and should no longer warn for an update that was just
adopted.
