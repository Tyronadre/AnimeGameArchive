"""Render the numbered PAC_MMD sprint from a copy of Lumine's fitted Blender scene.

Run with Blender --background <scene-copy.blend> --python scripts/render-lumine-loader.py.
This does not save or change the input scene. Only the generated startup assets are written.
"""
import subprocess
from pathlib import Path

import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/static/startup"
FRAMES = ROOT / ".tmp/lumine-loader-frames"
SOURCE = Path("2_Female_Motions/3_Center_Bone_Movement_Motions/241_Female-Sprint_A-Forward(Requires_UpperBody2).vmd")
WIDTH, HEIGHT, COUNT = 256, 320, 18
OUTPUT.mkdir(parents=True, exist_ok=True)
FRAMES.mkdir(parents=True, exist_ok=True)

scene = bpy.context.scene
armature = bpy.data.objects["荧_arm"]
character = bpy.data.objects["荧_mesh"]
selected = None
for track in armature.animation_data.nla_tracks:
    for strip in track.strips:
        if strip.action.name == "PAC_MMD_Sprint_A_Original_Arms":
            selected = track
    track.mute = True
assert selected is not None, "The fitted sprint from the numbered folder is required"
selected.mute = False
armature.animation_data.action = None
action = selected.strips[0].action

# Choose a low-discontinuity full stride without synthesizing another motion.
curves = []
for layer in action.layers:
    for strip in layer.strips:
        for slot in action.slots:
            bag = strip.channelbag(slot)
            if bag:
                curves.extend(bag.fcurves)
start = min(range(91, 145), key=lambda frame: sum(
    (curve.evaluate(frame + COUNT) - curve.evaluate(frame)) ** 2 for curve in curves
))
print(f"LOADER: using authored sprint frames {start}..{start + COUNT - 1}", flush=True)

if scene.rigidbody_world:
    cache = scene.rigidbody_world.point_cache
    if cache.is_baked:
        with bpy.context.temp_override(point_cache=cache):
            bpy.ops.ptcache.free_bake()
    cache.frame_start, cache.frame_end = 1, start + COUNT
    scene.frame_start, scene.frame_end = 1, start + COUNT
    scene.rigidbody_world.enabled = True
    scene.frame_set(1)
    print("LOADER: settling the fitted clothing physics", flush=True)
    with bpy.context.temp_override(point_cache=cache):
        bpy.ops.ptcache.bake(bake=True)

for obj in scene.objects:
    if obj.type in {"MESH", "LIGHT"} and obj != character:
        obj.hide_render = True
character.hide_render = False

def area_light(name, location, power, size):
    data = bpy.data.lights.new(name, "AREA")
    data.energy, data.shape, data.size = power, "DISK", size
    obj = bpy.data.objects.new(name, data)
    scene.collection.objects.link(obj)
    obj.location = location
    obj.rotation_euler = (Vector((0, 0, .8)) - obj.location).to_track_quat("-Z", "Y").to_euler()

area_light("Loader key", (-2.8, -3.5, 4), 450, 4)
area_light("Loader fill", (3, -1, 2), 220, 3)
area_light("Loader rim", (0, 3, 3), 320, 3)
camera_data = bpy.data.cameras.new("Loader camera")
camera = bpy.data.objects.new("Loader camera", camera_data)
scene.collection.objects.link(camera)
camera.location = (-3, -1.1, 1.05)
camera.rotation_euler = (Vector((0, -.015, .77)) - camera.location).to_track_quat("-Z", "Y").to_euler()
camera_data.type, camera_data.ortho_scale = "ORTHO", 1.83
scene.camera = camera
scene.render.engine = "BLENDER_EEVEE"
scene.render.resolution_x, scene.render.resolution_y = WIDTH, HEIGHT
scene.render.resolution_percentage = 100
scene.render.film_transparent = True
scene.render.image_settings.file_format = "PNG"
scene.render.image_settings.color_mode = "RGBA"
scene.render.image_settings.compression = 85
scene.view_settings.view_transform = "AgX"
scene.view_settings.look = "None"
scene.view_settings.exposure, scene.view_settings.gamma = 0, 1
if scene.world:
    scene.world.color = (.35, .35, .35)

for index in range(COUNT):
    scene.frame_set(start + index)
    scene.render.filepath = str(FRAMES / f"{index:02d}.png")
    print(f"LOADER: rendering {index + 1}/{COUNT}", flush=True)
    bpy.ops.render.render(write_still=True)

# Palette compression keeps the entire loop small enough for the first page load.
subprocess.run(["node", str(ROOT / "scripts/pack-lumine-loader.cjs"), str(start)], check=True)
print("LOADER: complete", flush=True)
