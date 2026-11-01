# Character page entrances

The character detail hero renders artwork and facts immediately. The existing
element palette, light/dark themes, Traveler selection, planning forms, ownership
and calculators remain part of the page. No animation is needed to read or edit
character data.

The initial presentation uses existing wish/card/icon artwork, or a styled initial
when no image loads. After window load and browser idle time, a small descriptor
request checks model availability. Compatible models can be opened explicitly in
a large modal inspection stage with room for the character and controls. Artwork
remains the default on every device until the user requests the model.
The renderer and model assets are not requested on the startup loading document,
character catalog cards, or missing-model pages.

The stage has persistent Pause motion and Artwork controls. When the model is
visible, drag to orbit the camera and scroll to zoom. Inspection controls select
a compatible numbered-pack idle, in-place walk, jog or sprint, plus lighting and
backdrop presets. Dendro characters open into a high-detail panoramic forest
sanctuary with softly animated fireflies. The panorama is loaded only when the
Dendro environment is selected. Character and environment meshes are not pickable,
so pointer input is reserved for camera movement. Offscreen stages and hidden
tabs stop rendering. Pausing or enabling reduced motion stops model playback.
Asset failures return to artwork and expose a retry. Page exit disposes the
renderer, while back/forward cache suspension can resume it.

## Model library

`genshin.presentation.model-directory` defaults to the existing local library:

`E:/Modding/Genshin/models/02_translated`

Set `GENSHIN_MODEL_DIRECTORY` to use another location. Its relative folder/file
layout must match `data/character-stages.json`; regenerate that allowlist after
renaming or replacing library folders. Missing libraries do not prevent startup.
They use artwork for external models. The library is read only.

The prepared allowlist maps 47 character identities from 49 folders. It selects
character bodies rather than props and prefers a base outfit over a separate skin.
Alternate aliases account for translated folder/model names. Kinich is currently
excluded because its selected model references a missing `hair_s.bmp`. Availability
also checks all referenced files at request time; repairs can be indexed again
without substituting another character's model.

Lumine has a portable PMX export of the fitted Blender copy and its textures under
`static/character-stage/models/lumine`. The fitted 0.15 hip-clearance shape value
is applied to that mesh; the original model folder, live Blender scene, sprint
action, original arm swing and clothing settings are not saved over. Model notice
and provenance accompany the export. The character detail stage loads the local
single-threaded MMD physics runtime for models with rigid bodies. It holds physics
state through animation loops, and generated idle clips now end on their opening
pose. If WebAssembly physics cannot initialize, the model falls back to bone
animation. The detail view uses one explicit inspection stage. Its idle/walk/jog/
sprint choices are filtered to the model's female, male or child rig profile.
Numbered locomotion clips have center-bone forward travel removed during
preparation so they play in place. Lighting presets adjust stage lights, and
backdrop choices style the stage surface. Page entrance camera and model reveal
animations are disabled.

Descriptors and assets use authenticated endpoints. Only the PMX and texture paths
in the selected manifest entry can be served. Directory traversal, unlisted files
and symlink escapes are rejected. Responses expose context-relative URLs, never
machine paths. `/archive/` deployment is covered by tests.

For deployment on another host, mount/configure the same library or regenerate
the index for that host's model files. Only the fitted Lumine model is packaged;
the other external source folders are not copied into the application.

## Authored idles and rendering

Only numbered packs supply motions. Each partial idle is completed with the
missing channels from the matching walk-to-stop final pose, with accumulated
forward travel removed from the center/foot IK controllers:

| Profile | Idle | Base pose |
| --- | --- | --- |
| Female | `2_Female_Motions/2_Idle_Motions/290L-290L_Female-Idle_L` | numbered female `202_Female-Walk-Forward_to_Stop` |
| Male | `1_Male_Motions/2_Idle_Motions/190-190_Male_A-Idle` | numbered male `102_Male_A-Walk-Forward_to_Stop` |
| Child | `3_Child_Motions/2_Idle_Motions/390-390_Child-Idle` | numbered child `302_Child-Walk-Forward_to_Idle` |

All names above refer to the `(Requires_UpperBody2).vmd` files. Source paths,
SHA-256 hashes, base frames and travel removal are recorded in
`static/character-stage/motions/provenance.json`. Models without the required rig
fall back to artwork before becoming visible, avoiding an uninitialized T-pose.

The runtime reuses the existing local PMX Character Studio's Babylon and Babylon
MMD bundles. They are vendored unchanged with their hashed WASM physics assets,
project links and license files in `character-stage/vendor`. There is no runtime
CDN dependency. The [MMD runtime reference](https://noname0310.github.io/babylon-mmd/docs/reference/runtime/mmd-runtime/)
documents playback and the runtime's optional physics implementation.

The stage registers Babylon-MMD's standard material builder and outline renderer,
so PMX toon textures and enabled edge settings are used with a softer frontal
fill light. These source models do not contain Genshin's proprietary face-shadow
maps or game shader; differences in the source face texture and geometry remain.

## Regenerating

To refresh the fitted mesh, use a copy of the fitted source scene:

```powershell
& 'C:/Program Files/Blender Foundation/Blender 5.2/blender.exe' --background .tmp/loader-lumine-source.blend --python scripts/export-fitted-lumine-stage.py
node scripts/prepare-character-stages.cjs
node scripts/prepare-character-inspection-motions.cjs
```

The preparation scripts read the configured library (or the existing E: folder),
numbered VMDs under `.tmp/model-download-test/animations`, and the existing local
prototype runtime bundles. It writes the model allowlist, complete idle VMDs,
in-place inspection motions, runtime copies and fitted Lumine export. They never save the Blender input scene
or writes to the external model/animation source folders. Normal app builds need
none of the regeneration tools or `.tmp` inputs.

## Verification

- `CharacterStageCatalogTest`: portable fitted model, completeness checks,
  allowlisted file access, traversal rejection and complete numbered idle assets.
- `CharacterStageIntegrationTest`: authentication, context-relative descriptors,
  portable model responses, missing models and the real Thymeleaf planning page.
- `node scripts/check-character-stage.cjs`: real fitted Lumine, Nefer, Flins,
  Sigewinne and Gaming models; female/male/child idles; TGA textures; pause/resume;
  continued playback across multiple idle cycles;
  offscreen suspension; no repeated entrance; runtime reduced-motion changes;
  model failure/retry; missing-model fallback; mobile layout; light/dark themes;
  context paths; and the actual Spring-rendered page's CSRF form.
- Existing companion and startup browser checks retain first paint, corner
  handoff and navigation continuity coverage. The focused Spring suite has
  16 passing tests across startup and presentation.

Visual previews are written under `.tmp/character-stage-*.png`.
