"""Export a stage copy of the fitted Lumine mesh, without saving the source scene."""
from pathlib import Path
import bpy
ROOT = Path(__file__).resolve().parents[1]
out = ROOT / '.tmp/fitted-lumine-stage'
out.mkdir(parents=True, exist_ok=True)
bpy.ops.preferences.addon_enable(module='bl_ext.blender_org.mmd_tools')
scene = bpy.context.scene
if scene.rigidbody_world:
    scene.rigidbody_world.enabled = False
arm = bpy.data.objects['荧_arm']
arm.animation_data_clear()
for bone in arm.pose.bones:
    bone.matrix_basis.identity()
mesh = bpy.data.objects['荧_mesh']
if mesh.data.shape_keys:
    mesh.data.shape_keys.animation_data_clear()
    for key in mesh.data.shape_keys.key_blocks:
        if key.name != 'Basis': key.value = .15 if key.name == 'Run - skirt hip clearance' else 0
    # Preserve the fitted hip clearance in the exported base mesh.
    bpy.ops.object.select_all(action='DESELECT')
    mesh.select_set(True);bpy.context.view_layer.objects.active=mesh
    bpy.ops.object.shape_key_remove(all=True, apply_mix=True)
bpy.ops.object.select_all(action='DESELECT')
root = bpy.data.objects['荧']
root.select_set(True);bpy.context.view_layer.objects.active=root
bpy.context.view_layer.update()
bpy.ops.mmd_tools.export_pmx(filepath=str(out/'lumine.pmx'),scale=12.5,copy_textures_mode='SKIP_EXISTING',visible_meshes_only=False,log_level='WARNING')
print('FITTED_LUMINE_STAGE_COMPLETE',flush=True)
