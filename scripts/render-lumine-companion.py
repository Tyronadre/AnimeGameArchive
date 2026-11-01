"""Render the numbered authored idle on a COPY of the fitted scene, without saving it.

Blender --background .tmp/loader-lumine-source.blend --python scripts/render-lumine-companion.py
The complete numbered walk-to-stop supplies missing base-pose channels, including feet.
The numbered idle supplies all moving body channels. No synthetic arm offsets.
"""
import subprocess
import struct
import sys
from pathlib import Path
import bpy
from mathutils import Vector

bpy.ops.preferences.addon_enable(module='bl_ext.blender_org.mmd_tools')

ROOT = Path(__file__).resolve().parents[1]
MOTIONS = ROOT / '.tmp/model-download-test/animations/2_Female_Motions'
clip = sys.argv[sys.argv.index('--clip') + 1] if '--clip' in sys.argv else 'idle'
assert clip in {'idle', 'start', 'stop'}
FRAMES = ROOT / ('.tmp/lumine-companion-frames' if clip == 'idle' else f'.tmp/lumine-companion-{clip}-frames')
FRAMES.mkdir(parents=True, exist_ok=True)
scene = bpy.context.scene
armature, character = bpy.data.objects['荧_arm'], bpy.data.objects['荧_mesh']
cache = scene.rigidbody_world.point_cache
if cache.is_baked:
    with bpy.context.temp_override(point_cache=cache):
        bpy.ops.ptcache.free_bake()
scene.rigidbody_world.enabled = False
for track in armature.animation_data.nla_tracks:
    track.mute = True
armature.animation_data.action = None
for bone in armature.pose.bones:
    bone.matrix_basis.identity()
    bone.mmd_ik_toggle = True
bpy.ops.object.select_all(action='DESELECT')
armature.select_set(True)
bpy.context.view_layer.objects.active = armature
scene.frame_set(1)
base_path = MOTIONS / '3_Center_Bone_Movement_Motions/202_Female-Walk-Forward_to_Stop(Requires_UpperBody2).vmd'
bpy.ops.mmd_tools.import_vmd(filepath=str(base_path), scale=.08, margin=0, bone_mapper='PMX', create_new_action=True, use_nla=False)
scene.frame_set(334)
# Take the complete stopped pose and remove only its accumulated forward travel.
data = base_path.read_bytes()
centers = []
for index in range(struct.unpack_from('<I', data, 50)[0]):
    offset = 54 + index * 111
    if data[offset:offset + 15].split(b'\0')[0].decode('shift_jis') == 'センター':
        centers.append((struct.unpack_from('<I', data, offset + 15)[0], struct.unpack_from('<f', data, offset + 27)[0]))
travel = Vector((0, -max(centers)[1] * .08, 0))
for bone in armature.pose.bones:
    if bone.mmd_bone.name_j in {'センター', '左足ＩＫ', '右足ＩＫ'}:
        bone.location -= bone.bone.matrix_local.to_3x3().inverted() @ travel
base = {bone.name: bone.matrix_basis.copy() for bone in armature.pose.bones}
armature.animation_data.action = None
scene.frame_set(1)
for name, matrix in base.items():
    armature.pose.bones[name].matrix_basis = matrix
motion_path = {
    'idle': '2_Idle_Motions/290L-290L_Female-Idle_L(Requires_UpperBody2).vmd',
    'start': '4_Master_Root_Movement_Motions/290L-220_Female-Idle_L_to_Jog-Forward(Requires_UpperBody2).vmd',
    'stop': '4_Master_Root_Movement_Motions/220-end_Female-Jog-Forward_to_Idle(Requires_UpperBody2).vmd',
}[clip]
scene.frame_set(1 if clip == 'idle' else 67)
bpy.ops.mmd_tools.import_vmd(filepath=str(MOTIONS / motion_path), scale=.08, margin=0, bone_mapper='PMX', create_new_action=True, use_nla=False)
action = armature.animation_data.action
root_name = next(b.name for b in armature.pose.bones if b.mmd_bone.name_j == '全ての親')
foot_names = [b.name for b in armature.pose.bones if b.mmd_bone.name_j in {'左足ＩＫ', '右足ＩＫ'}]
# Idle repeats for warm-up. Transition clips hold their initial pose for 66
# frames, then play once. Remove master travel/dummy keys, retaining body motion.
for layer in action.layers:
    for strip in layer.strips:
        for slot in action.slots:
            bag = strip.channelbag(slot)
            if bag:
                for curve in bag.fcurves:
                    if clip == 'idle':
                        curve.modifiers.new('CYCLES')
                    elif curve.data_path == f'pose.bones["{root_name}"].location':
                        for key in curve.keyframe_points:
                            key.co.y = key.handle_left.y = key.handle_right.y = 0
                    elif any(curve.data_path == f'pose.bones["{name}"].location' for name in foot_names):
                        for key in curve.keyframe_points:
                            key.co.y *= .94
                            key.handle_left.y *= .94
                            key.handle_right.y *= .94
count = 26 if clip == 'stop' else 22
start = 83 if clip == 'start' else 67
end = start + (count - 1) * 3
scene.frame_start, scene.frame_end = 1, end + 1
cache.frame_start, cache.frame_end = 1, end + 1
scene.rigidbody_world.enabled = True
scene.frame_set(1)
with bpy.context.temp_override(point_cache=cache):
    bpy.ops.ptcache.bake(bake=True)
for obj in scene.objects:
    if obj.type in {'MESH', 'LIGHT'} and obj != character:
        obj.hide_render = True
character.hide_render = False
def light(name, location, power, size):
    data = bpy.data.lights.new(name, 'AREA')
    data.energy, data.shape, data.size = power, 'DISK', size
    obj = bpy.data.objects.new(name, data)
    scene.collection.objects.link(obj)
    obj.location = location
    obj.rotation_euler = (Vector((0, 0, .8)) - obj.location).to_track_quat('-Z', 'Y').to_euler()
light('Companion key', (-2.8, -3.5, 4), 450, 4)
light('Companion fill', (3, -1, 2), 220, 3)
light('Companion rim', (0, 3, 3), 320, 3)
data = bpy.data.cameras.new('Companion camera')
camera = bpy.data.objects.new('Companion camera', data)
scene.collection.objects.link(camera)
camera.location = (-.7, -3.5, 1.0)
camera.rotation_euler = (Vector((0, 0, .76)) - camera.location).to_track_quat('-Z', 'Y').to_euler()
data.type, data.ortho_scale = 'ORTHO', 1.83
scene.camera = camera
scene.render.engine = 'BLENDER_EEVEE'
scene.render.resolution_x, scene.render.resolution_y = 256, 320
scene.render.resolution_percentage = 100
scene.render.film_transparent = True
scene.render.image_settings.file_format, scene.render.image_settings.color_mode = 'PNG', 'RGBA'
scene.view_settings.view_transform, scene.view_settings.look = 'AgX', 'None'
scene.view_settings.exposure, scene.view_settings.gamma = 0, 1
if scene.world:
    scene.world.color = (.35, .35, .35)
front, profile = Vector((-.7, -3.5, 1.0)), Vector((-3, -1.1, 1.05))
for index in range(count):
    scene.frame_set(start + index * 3)
    if clip != 'idle':
        factor = index / (count - 1)
        # Match the existing sprint view to the idle view while the authored
        # motion settles. Only the camera changes; source arm motion is intact.
        camera.location = front.lerp(profile, factor if clip == 'start' else 1 - factor)
        camera.rotation_euler = (Vector((0, 0, .76)) - camera.location).to_track_quat('-Z', 'Y').to_euler()
    scene.render.filepath = str(FRAMES / f'{index:02d}.png')
    print(f'COMPANION {clip}: render {index + 1}/{count}', flush=True)
    bpy.ops.render.render(write_still=True)
subprocess.run(['node', str(ROOT / 'scripts/pack-lumine-loader.cjs'), f'--companion-{clip}'], check=True)
