"""Render chair rotating around vertical axis as PNG sequence with transparent BG.
Run via: blender --background --python render_chair.py -- <glb_path> <out_dir>
"""
import bpy
import math
import os
import sys
from mathutils import Vector

argv = sys.argv
argv = argv[argv.index("--") + 1 :] if "--" in argv else []
chair_glb = argv[0] if len(argv) > 0 else r"C:\Users\user\Desktop\portfolio-public\assets\chair\monoblock_CHAIR_matteblack.glb"
out_dir = argv[1] if len(argv) > 1 else r"C:\Users\user\Desktop\eye-android\chair_render"

os.makedirs(out_dir, exist_ok=True)

# Wipe scene
bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete(use_global=False)
for collection in list(bpy.data.collections):
    bpy.data.collections.remove(collection)

# Import
bpy.ops.import_scene.gltf(filepath=chair_glb)
mesh_objects = [obj for obj in bpy.data.objects if obj.type == "MESH"]
if not mesh_objects:
    raise RuntimeError("No mesh imported from glb")

# Compute combined bbox and place pivot at chair center
min_co = Vector((float("inf"),) * 3)
max_co = Vector((float("-inf"),) * 3)
for obj in mesh_objects:
    for v in obj.bound_box:
        world_v = obj.matrix_world @ Vector(v)
        for i in range(3):
            if world_v[i] < min_co[i]:
                min_co[i] = world_v[i]
            if world_v[i] > max_co[i]:
                max_co[i] = world_v[i]

center = (min_co + max_co) / 2
size = max_co - min_co
max_dim = max(size.x, size.y, size.z)

bpy.ops.object.empty_add(location=center)
pivot = bpy.context.active_object
pivot.name = "ChairPivot"

# Parent meshes to pivot, preserve world transform
for obj in mesh_objects:
    matrix_copy = obj.matrix_world.copy()
    obj.parent = pivot
    obj.matrix_world = matrix_copy

# Animate Z-axis (vertical in Blender) rotation
TOTAL_FRAMES = 72  # 2.4s @ 30fps to match Android timing
FPS = 30

scene = bpy.context.scene
scene.frame_start = 1
scene.frame_end = TOTAL_FRAMES
scene.render.fps = FPS

pivot.rotation_mode = "XYZ"
pivot.rotation_euler = (0, 0, 0)
pivot.keyframe_insert(data_path="rotation_euler", frame=1)
# +1 frame for seamless loop: frame 73 is "where we'd be" if there were one,
# but we render only 1..72 so the last sampled is at 355°.
pivot.rotation_euler = (0, 0, math.radians(360))
pivot.keyframe_insert(data_path="rotation_euler", frame=TOTAL_FRAMES + 1)

if pivot.animation_data and pivot.animation_data.action:
    for fcurve in pivot.animation_data.action.fcurves:
        for kp in fcurve.keyframe_points:
            kp.interpolation = "LINEAR"

# Camera: place outside on -Y (toward camera), slightly above
camera_distance = max_dim * 1.7
camera_height = center.z + max_dim * 0.15
bpy.ops.object.camera_add(location=(0, -camera_distance, camera_height))
camera = bpy.context.active_object
# Aim at the chair center
direction = center - camera.location
rot_quat = direction.to_track_quat("-Z", "Y")
camera.rotation_euler = rot_quat.to_euler()
scene.camera = camera

# 3-point lighting
def make_light(name, location, energy, size_mul=1.0):
    bpy.ops.object.light_add(type="AREA", location=location)
    light = bpy.context.active_object
    light.name = name
    light.data.energy = energy
    light.data.size = max_dim * size_mul
    return light

make_light("Key", (max_dim * 1.5, -max_dim * 1.5, center.z + max_dim * 1.2), 800, 1.5)
make_light("Fill", (-max_dim * 1.2, -max_dim * 0.8, center.z + max_dim * 0.4), 250, 1.5)
make_light("Rim", (0, max_dim * 1.2, center.z + max_dim * 1.5), 600, 1.5)

# Ensure matte black on all materials
for obj in mesh_objects:
    for slot in obj.material_slots:
        mat = slot.material
        if mat is None:
            continue
        if not mat.use_nodes:
            mat.use_nodes = True
        principled = next((n for n in mat.node_tree.nodes if n.type == "BSDF_PRINCIPLED"), None)
        if principled:
            principled.inputs["Base Color"].default_value = (0.018, 0.018, 0.018, 1.0)
            principled.inputs["Roughness"].default_value = 0.65
            if "Metallic" in principled.inputs:
                principled.inputs["Metallic"].default_value = 0.0
            for spec_key in ("Specular IOR Level", "Specular"):
                if spec_key in principled.inputs:
                    principled.inputs[spec_key].default_value = 0.25
                    break

# Render config
scene.render.film_transparent = True
scene.render.resolution_x = 384
scene.render.resolution_y = 384
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = "PNG"
scene.render.image_settings.color_mode = "RGBA"
scene.render.image_settings.compression = 15
scene.render.filepath = os.path.join(out_dir, "frame_")

# Pick the best available Eevee
available_engines = [e.identifier for e in bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items]
if "BLENDER_EEVEE_NEXT" in available_engines:
    scene.render.engine = "BLENDER_EEVEE_NEXT"
elif "BLENDER_EEVEE" in available_engines:
    scene.render.engine = "BLENDER_EEVEE"
else:
    scene.render.engine = "CYCLES"
print(f"Engine: {scene.render.engine}")

# Render 1..TOTAL_FRAMES (seamless: frame 1 visually equals frame TOTAL_FRAMES+1)
scene.frame_end = TOTAL_FRAMES
bpy.ops.render.render(animation=True)
print(f"Done: {TOTAL_FRAMES} frames -> {out_dir}")
