"""Build an exportable, fully 3D Dendro forest stage in an isolated Blender process."""
import bpy
import math
import random
import json
import struct
import os
import tempfile
from mathutils import Vector

PREFIX = "DendroEnv__"
OUTPUT = r"C:\Users\Henrik\IdeaProjects\GenshinApp\src\main\resources\static\character-stage\environments\dendro-forest-environment.glb"
ASSET_DIR = os.path.join(os.path.dirname(__file__), "assets", "dendro-quaternius", "glTF")
RNG = random.Random(20261101)
window = bpy.context.window
original_scene = window.scene if window else None
isolated_scene = window is None
scene = bpy.context.scene if isolated_scene else bpy.data.scenes.new(PREFIX + "TemporaryScene")
if window:
    window.scene = scene
else:
    for obj in list(scene.objects):
        bpy.data.objects.remove(obj, do_unlink=True)
scene.world = bpy.data.worlds.new(PREFIX + "World")
created = []

def material(name, color, roughness=0.82, metallic=0.0):
    m = bpy.data.materials.new(PREFIX + name)
    m.diffuse_color = (*color, 1.0)
    m.use_nodes = True
    shader = m.node_tree.nodes.get("Principled BSDF")
    shader.inputs["Base Color"].default_value = (*color, 1.0)
    shader.inputs["Roughness"].default_value = roughness
    shader.inputs["Metallic"].default_value = metallic
    return m

leaf_mats = [
    material("CanopyDeep", (0.035, 0.20, 0.105)),
    material("CanopyEmerald", (0.055, 0.34, 0.17)),
    material("CanopyJade", (0.12, 0.47, 0.22)),
    material("CanopySunlit", (0.31, 0.58, 0.22)),
    material("CanopyTeal", (0.045, 0.29, 0.25)),
]
bark = material("Bark", (0.16, 0.095, 0.055))
bark_light = material("BarkLight", (0.25, 0.15, 0.075))
stone_mats = [
    material("StoneCool", (0.29, 0.36, 0.34)),
    material("StoneMoss", (0.22, 0.31, 0.27)),
    material("StoneLight", (0.40, 0.43, 0.36)),
]
moss = material("Moss", (0.11, 0.29, 0.13))
soil_mats = [material("ForestSoil", (0.72, 0.94, 0.66)), material("ForestSoilLight", (0.15, 0.22, 0.12))]
water = material("StillWater", (0.035, 0.22, 0.20), 0.25, 0.12)
inlay = material("DendroInlay", (0.32, 0.57, 0.18), 0.34, 0.08)
ground_shader=soil_mats[0].node_tree.nodes.get("Principled BSDF")
ground_color=bpy.data.images.load(os.path.join(os.path.dirname(__file__),"assets","ambientcg-grass001","Grass001_1K-JPG_Color.jpg"),check_existing=True)
ground_color_node=soil_mats[0].node_tree.nodes.new("ShaderNodeTexImage"); ground_color_node.image=ground_color
soil_mats[0].node_tree.links.new(ground_color_node.outputs["Color"],ground_shader.inputs["Base Color"])
ground_normal=bpy.data.images.load(os.path.join(os.path.dirname(__file__),"assets","ambientcg-grass001","Grass001_1K-JPG_NormalGL.jpg"),check_existing=True)
ground_normal.colorspace_settings.name="Non-Color"
ground_normal_node=soil_mats[0].node_tree.nodes.new("ShaderNodeTexImage"); ground_normal_node.image=ground_normal
ground_normal_node.image.colorspace_settings.name="Non-Color"
ground_normal_map=soil_mats[0].node_tree.nodes.new("ShaderNodeNormalMap")
soil_mats[0].node_tree.links.new(ground_normal_node.outputs["Color"],ground_normal_map.inputs["Color"])
soil_mats[0].node_tree.links.new(ground_normal_map.outputs["Normal"],ground_shader.inputs["Normal"])
ground_shader.inputs["Roughness"].default_value=.94

def finish(obj, mat=None, bevel=0.0, smooth=False):
    obj.name = PREFIX + obj.name
    created.append(obj)
    if mat:
        obj.data.materials.append(mat)
    if bevel > 0:
        mod = obj.modifiers.new("Soft worn edges", "BEVEL")
        mod.width = bevel
        mod.segments = 3
        obj.modifiers.new("Weighted corner normals", "WEIGHTED_NORMAL")
    if smooth and obj.type == 'MESH':
        for polygon in obj.data.polygons:
            polygon.use_smooth = True
    return obj

def cube(name, location, dimensions, mat, bevel=0.0, rotation=0.0):
    bpy.ops.mesh.primitive_cube_add(size=1, location=location)
    obj = bpy.context.object
    obj.dimensions = dimensions
    obj.rotation_euler[2] = rotation
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    return finish(obj, mat, bevel)

def cylinder(name, location, radius, depth, mat, vertices=20, bevel=0.0):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=depth, location=location)
    return finish(bpy.context.object, mat, bevel)

def ico(name, location, scale, mat, subdivisions=2):
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=subdivisions, radius=1, location=location)
    obj = bpy.context.object
    obj.scale = scale
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    return finish(obj, mat, smooth=True)

def mesh_object(name, vertices, faces, mats, face_materials=None, smooth=False):
    mesh = bpy.data.meshes.new(PREFIX + name + "Mesh")
    mesh.from_pydata(vertices, [], faces)
    mesh.materials.clear()
    for mat in mats:
        mesh.materials.append(mat)
    mesh.update()
    obj = bpy.data.objects.new(PREFIX + name, mesh)
    scene.collection.objects.link(obj)
    created.append(obj)
    if face_materials:
        for polygon, index in zip(mesh.polygons, face_materials):
            polygon.material_index = index
    if smooth:
        for polygon in mesh.polygons:
            polygon.use_smooth = True
    return obj

def add_tapered_segment(verts, faces, start, end, radius_start, radius_end, sides=9):
    start, end = Vector(start), Vector(end)
    axis = (end-start).normalized()
    ref = Vector((0, 0, 1)) if abs(axis.z) < 0.88 else Vector((1, 0, 0))
    u = axis.cross(ref).normalized()
    v = axis.cross(u).normalized()
    base = len(verts)
    for center, radius in ((start, radius_start), (end, radius_end)):
        for j in range(sides):
            angle = 2*math.pi*j/sides
            p = center + radius*(math.cos(angle)*u + math.sin(angle)*v)
            verts.append(tuple(p))
    for j in range(sides):
        a, b = base+j, base+(j+1)%sides
        faces.append((a, b, b+sides, a+sides))
    faces.append(tuple(base+j for j in reversed(range(sides))))
    faces.append(tuple(base+sides+j for j in range(sides)))

def create_trunk(x, y, height, radius, idx):
    verts, faces = [], []
    levels, sides = 11, 18
    for level in range(levels):
        t = level/(levels-1)
        cx = x + math.sin(t*math.pi*1.4+idx)*radius*.25
        cy = y + math.sin(t*math.pi*1.1+idx*.7)*radius*.2
        z = height*t
        r = radius*(1-.70*t) * (1+.08*math.sin(level*1.7+idx))
        for j in range(sides):
            a = 2*math.pi*j/sides
            irregular = 1 + .055*math.sin(j*5+idx) + .025*math.sin(j*9+level)
            verts.append((cx+r*irregular*math.cos(a), cy+r*irregular*math.sin(a), z))
    for level in range(levels-1):
        for j in range(sides):
            a=level*sides+j; b=level*sides+(j+1)%sides
            faces.append((a,b,b+sides,a+sides))
    faces.append(tuple(reversed(range(sides))))
    faces.append(tuple((levels-1)*sides+j for j in range(sides)))
    trunk = mesh_object("TreeTrunk_%02d"%idx, verts, faces, [bark], smooth=True)

    verts, faces = [], []
    for r in range(7):
        angle = 2*math.pi*r/7 + idx*.37
        direction = Vector((math.cos(angle), math.sin(angle), 0))
        root_start=(x+direction.x*radius*.18,y+direction.y*radius*.18,.45)
        root_end=(x+direction.x*radius*2.0,y+direction.y*radius*2.0,.08)
        root_mid=(x+direction.x*radius*.85,y+direction.y*radius*.85,.28)
        add_tapered_segment(verts,faces,root_start,root_mid,radius*.40,radius*.22,8)
        add_tapered_segment(verts,faces,root_mid,root_end,radius*.22,.03,8)
    rootmesh=mesh_object("ButtressRoots_%02d"%idx,verts,faces,[bark_light],smooth=True)

    verts, faces = [], []
    crown_z=height*.69
    for j in range(7):
        a=2*math.pi*j/7+idx*.45
        start=(x,y,crown_z-RNG.uniform(0,.8))
        end=(x+math.cos(a)*RNG.uniform(3.2,5.0),y+math.sin(a)*RNG.uniform(3.2,5.0),height*RNG.uniform(.86,1.12))
        mid=(x+math.cos(a)*1.6,y+math.sin(a)*1.6,height*RNG.uniform(.76,.91))
        add_tapered_segment(verts,faces,start,mid,radius*.5,radius*.24,9)
        add_tapered_segment(verts,faces,mid,end,radius*.24,.10,8)
    branchmesh=mesh_object("CanopyBranches_%02d"%idx,verts,faces,[bark],smooth=True)
    return trunk, rootmesh, branchmesh

def leaf_batch(name, leaf_specs, mats):
    verts, faces, indices = [], [], []
    for base, direction, length, width, material_index in leaf_specs:
        base=Vector(base); direction=Vector(direction).normalized()
        helper=Vector((0,0,1)) if abs(direction.z)<.92 else Vector((0,1,0))
        side=direction.cross(helper).normalized()*width
        normal=side.normalized().cross(direction).normalized()
        crease=normal*length*.10
        top=[base,base+direction*.36+side+crease*.45,base+direction*.65+crease,
             base+direction*.36-side+crease*.45,base+direction]
        bottom=[p-normal*.025 for p in top]
        offset=len(verts); verts.extend([tuple(p) for p in top+bottom])
        faces.extend([(offset,offset+1,offset+2),(offset,offset+2,offset+3),(offset+3,offset+2,offset+4),
                      (offset+5,offset+7,offset+6),(offset+5,offset+8,offset+7),(offset+8,offset+6,offset+7)])
        indices.extend([material_index]*6)
    return mesh_object(name,verts,faces,mats,indices)

# Terrain is a shallow, irregular basin with blended leaf-litter patches.
grid, extent = 64, 25.0
verts=[]
for j in range(grid+1):
    y=-extent+2*extent*j/grid
    for i in range(grid+1):
        x=-extent+2*extent*i/grid
        radius=math.sqrt(x*x+y*y)
        h=max(0,radius-6.5)*.035 + .12*math.sin(x*.7)*math.sin(y*.55) + RNG.uniform(-.035,.035)
        verts.append((x,y,h))
faces=[]; face_mats=[]
for j in range(grid):
    for i in range(grid):
        a=j*(grid+1)+i; b=a+1; c=a+grid+2; d=a+grid+1
        faces.append((a,b,c,d))
        face_mats.append(0)
terrain=mesh_object("ForestTerrain",verts,faces,soil_mats,face_mats,smooth=True)
terrain_uv=terrain.data.uv_layers.new(name="UVMap")
for loop in terrain.data.loops:
    p=terrain.data.vertices[loop.vertex_index].co
    terrain_uv.data[loop.index].uv=(p.x/2.8,p.y/2.8)

# Stone observatory floor: concentric hand-laid pavers, worn rings and a quiet inlay.
cylinder("MossyFoundation",(0,0,.10),5.25,.22,stone_mats[1],64,.08)
cylinder("InnerFloor",(0,0,.23),4.55,.10,stone_mats[0],64,.06)
for i in range(40):
    angle=2*math.pi*i/40
    r=3.56+RNG.uniform(-.12,.12)
    x,y=r*math.cos(angle),r*math.sin(angle)
    dims=(RNG.uniform(.8,1.25),RNG.uniform(.65,.92),RNG.uniform(.15,.24))
    cube("RadialPaver_%02d"%i,(x,y,.36),dims,RNG.choice(stone_mats),.10,angle+math.pi/2)
for radius in (2.4,2.62,4.65):
    bpy.ops.mesh.primitive_torus_add(major_radius=radius,minor_radius=.045,major_segments=96,minor_segments=8,location=(0,0,.36))
    finish(bpy.context.object,inlay if radius==2.4 else stone_mats[2],smooth=True)
cylinder("CenterSeal",(0,0,.34),1.78,.12,stone_mats[1],64,.05)
for i in range(12):
    angle=i*2*math.pi/12
    cube("DendroMark_%02d"%i,(1.18*math.cos(angle),1.18*math.sin(angle),.425),(.13,.68,.025),inlay,.025,angle)

ASSET_TEMPLATES={}
def import_asset_instance(name, location, rotation, scale):
    template=ASSET_TEMPLATES.get(name)
    if template:
        source=template.copy(); source.data=template.data; scene.collection.objects.link(source)
    else:
        path=os.path.join(ASSET_DIR,name+".gltf")
        before=set(bpy.data.objects)
        bpy.ops.import_scene.gltf(filepath=path)
        imported=[obj for obj in bpy.data.objects if obj not in before and obj.type=='MESH']
        if not imported: raise RuntimeError("No mesh imported from "+path)
        source=imported[0]
        for extra in imported[1:]: bpy.data.objects.remove(extra,do_unlink=True)
        for mat in source.data.materials:
            if mat and not mat.name.startswith(PREFIX): mat.name=PREFIX+"Asset_"+mat.name
        ASSET_TEMPLATES[name]=source
    source.name=PREFIX+name+"_Instance_%03d"%len(created)
    source.location=location; source.rotation_euler[2]=rotation; source.scale=(scale,scale,scale)
    created.append(source)

# Textured, hand-authored CC0 assets frame the clearing.
tree_assets=("CommonTree_1","CommonTree_3","Pine_2")
for i in range(14):
    angle=2*math.pi*i/14+RNG.uniform(-.10,.10)
    if math.sin(angle)>.72: continue
    radius=RNG.uniform(9.5,15.5)
    x,y=radius*math.cos(angle),radius*math.sin(angle)
    import_asset_instance(RNG.choice(tree_assets),(x,y,0),RNG.uniform(0,2*math.pi),RNG.uniform(1.75,2.15))

# Moss-covered ruin ring: split columns, capitals, carved bands and broken lintels.
for i in range(8):
    angle=2*math.pi*i/8+.18
    if math.sin(angle)>.70: continue
    radius=8.5+RNG.uniform(-.35,.35)
    x,y=radius*math.cos(angle),radius*math.sin(angle)
    height=RNG.uniform(7.5,8.5)
    cylinder("RuinFoot_%02d"%i,(x,y,height*.035),.96,.30,stone_mats[1],16,.08)
    cylinder("RuinShaft_%02d"%i,(x,y,height*.52),.62,height,stone_mats[i%3],16,.06)
    for band_z in (height*.2,height*.78,height*.88):
        cylinder("CarvedBand_%02d"%i,(x,y,band_z),.72,.16,stone_mats[2],20,.035)
    cube("BrokenCapital_%02d"%i,(x,y,height+.18),(1.95,1.75,.45),stone_mats[i%3],.13,RNG.uniform(-.3,.3))
    for moss_i in range(7):
        a=RNG.uniform(0,2*math.pi)
        ico("RuinMoss_%02d_%02d"%(i,moss_i),
            (x+math.cos(a)*.52,y+math.sin(a)*.52,RNG.uniform(.2,height)),
            (RNG.uniform(.18,.4),RNG.uniform(.15,.32),RNG.uniform(.08,.20)),moss,1)
# Arches are stone voussoirs with a few intentionally missing stones.
for arch_i, angle in enumerate((math.pi/4,3*math.pi/4,5*math.pi/4,7*math.pi/4)):
    if math.sin(angle)>.58: continue
    cx,cy=8.5*math.cos(angle),8.5*math.sin(angle)
    tangent=Vector((-math.sin(angle),math.cos(angle),0))
    base=Vector((cx,cy,0)); arch_h=8.0
    for seg in range(9):
        if seg in (7,) and arch_i%2==0: continue
        t=seg/8
        p=base+tangent*(t*6.3-3.15)+Vector((0,0,arch_h+1.55*math.sin(math.pi*t)))
        cube("ArchStone_%02d_%02d"%(arch_i,seg),tuple(p),(.82,.68,.62),stone_mats[(seg+arch_i)%3],.09,angle)

# Textured shrubs and ferns soften the clearing edge and remain genuine 3D models.
ground_assets=("Fern_1","Plant_1","Clover_1","Grass_Common_Tall")
for i in range(40):
    angle=RNG.uniform(0,2*math.pi); radius=RNG.uniform(5.7,11.8)
    if math.sin(angle)>.62 and radius>8.0: continue
    x,y=radius*math.cos(angle),radius*math.sin(angle)
    import_asset_instance(RNG.choice(ground_assets),(x,y,0),RNG.uniform(0,2*math.pi),RNG.uniform(1.15,1.65))

# Still pool at one edge, with a stepping-stone bank and floating leaves.
pool_x,pool_y=13.2,-7.2
bpy.ops.mesh.primitive_circle_add(vertices=64,radius=5.0,fill_type='NGON',location=(pool_x,pool_y,.04))
finish(bpy.context.object,water)
for i in range(14):
    angle=RNG.uniform(0,2*math.pi); radius=RNG.uniform(2.3,4.7)
    x,y=pool_x+radius*math.cos(angle),pool_y+radius*math.sin(angle)
    if i%3:
        ico("PoolLily_%02d"%i,(x,y,.12),(RNG.uniform(.32,.62),RNG.uniform(.22,.48),.055),leaf_mats[2],1)
    else:
        ico("PoolStone_%02d"%i,(x,y,.22),(.55,.47,.23),stone_mats[i%3],1)

# A giant canopy gap lets the stage lights and character remain the focal point.
# No camera, lights or character objects are exported with the environment.
scene.world.color=(.035,.075,.055)
scene.render.engine='BLENDER_EEVEE'
scene.render.resolution_x=640
scene.render.resolution_y=640

def export_glb_without_depsgraph():
    """Serialize static mesh datablocks directly; this avoids Blender's crashing depsgraph exporter."""
    blob=bytearray(); views=[]; accessors=[]; meshes=[]; nodes=[]; global_buckets={}
    def add_bytes(data, target, byte_stride=None):
        while len(blob)%4: blob.append(0)
        offset=len(blob); blob.extend(data)
        view={"buffer":0,"byteOffset":offset,"byteLength":len(data)}
        if target: view["target"]=target
        if byte_stride: view["byteStride"]=byte_stride
        views.append(view); return len(views)-1
    images=[]; textures=[]; image_ids={}; texture_ids={}; materials=[]; material_ids={}; temporary_textures=[]
    def texture_from_socket(material, socket_name):
        shader=material.node_tree.nodes.get("Principled BSDF") if material.use_nodes else None
        if not shader: return None
        pending=[link.from_node for link in shader.inputs[socket_name].links]; visited=set()
        image=None
        while pending and image is None:
            node=pending.pop()
            if node in visited: continue
            visited.add(node)
            if node.type=='TEX_IMAGE' and node.image:
                image=node.image; break
            for socket in node.inputs:
                pending.extend(link.from_node for link in socket.links)
        if not image: return None
        original_path=bpy.path.abspath(image.filepath)
        if original_path not in image_ids:
            filepath=original_path
            width,height=map(int,image.size)
            if max(width,height)>1024:
                scale=1024/max(width,height)
                image.scale(max(1,int(width*scale)),max(1,int(height*scale)))
                fd,filepath=tempfile.mkstemp(prefix="dendro-texture-",suffix=".png")
                os.close(fd); temporary_textures.append(filepath)
                original_raw=image.filepath_raw
                image.filepath_raw=filepath; image.file_format='PNG'; image.save()
                image.filepath_raw=original_raw
            with open(filepath,'rb') as source: content=source.read()
            view=add_bytes(content,None)
            image_ids[original_path]=len(images)
            mime="image/jpeg" if filepath.lower().endswith((".jpg",".jpeg")) else "image/png"
            images.append({"name":os.path.basename(filepath),"bufferView":view,"mimeType":mime})
        if original_path not in texture_ids:
            textures.append({"source":image_ids[original_path],"sampler":0})
            texture_ids[original_path]=len(textures)-1
        return texture_ids[original_path]
    for mat in bpy.data.materials:
        if not mat.name.startswith(PREFIX): continue
        shader=mat.node_tree.nodes.get("Principled BSDF") if mat.use_nodes else None
        color=tuple(float(c) for c in mat.diffuse_color[:4])
        metallic=float(shader.inputs["Metallic"].default_value) if shader else 0.0
        roughness=float(shader.inputs["Roughness"].default_value) if shader else .8
        base_texture=texture_from_socket(mat,"Base Color") if shader else None
        normal_texture=texture_from_socket(mat,"Normal") if shader else None
        pbr={"baseColorFactor":color,
             "metallicFactor":metallic,"roughnessFactor":roughness}
        if base_texture is not None: pbr["baseColorTexture"]={"index":base_texture}
        definition={"name":mat.name,"pbrMetallicRoughness":pbr}
        if normal_texture is not None: definition["normalTexture"]={"index":normal_texture}
        if base_texture is not None and any(part in mat.name.lower() for part in ("leaf","leaves","fern","bush","flower","clover","grass","pine")):
            definition.update({"alphaMode":"MASK","alphaCutoff":.2,"doubleSided":True})
        material_ids[mat]=len(materials); materials.append(definition)
    for obj in created:
        if obj.type!='MESH' or not obj.data.polygons: continue
        mesh=obj.data; mesh.calc_loop_triangles()
        buckets={}
        normal_matrix=obj.matrix_world.to_3x3().inverted().transposed()
        uv_layer=mesh.uv_layers.active
        color_layer=mesh.color_attributes.active_color
        for tri in mesh.loop_triangles:
            poly=mesh.polygons[tri.polygon_index]
            slots=obj.material_slots
            mat=slots[poly.material_index].material if poly.material_index<len(slots) else None
            mat_id=material_ids.get(mat)
            if mat_id is None: continue
            bucket=buckets.setdefault(mat_id,[])
            for loop_index in tri.loops:
                loop=mesh.loops[loop_index]
                point=obj.matrix_world @ mesh.vertices[loop.vertex_index].co
                normal=(normal_matrix @ loop.normal).normalized()
                uv=tuple(uv_layer.data[loop_index].uv) if uv_layer else (0.0,0.0)
                if color_layer:
                    color_data=color_layer.data[loop_index] if color_layer.domain=='CORNER' else color_layer.data[loop.vertex_index]
                    color=tuple(color_data.color)
                else: color=(1.0,1.0,1.0,1.0)
                # Blender Z-up to glTF Y-up.
                bucket.append(((point.x,point.z,-point.y),(normal.x,normal.z,-normal.y),uv,color))
        for mat_id, vertices in buckets.items():
            global_buckets.setdefault(mat_id,[]).extend(vertices)
    primitives=[]
    for mat_id, vertices in global_buckets.items():
            positions=[]; normals=[]; uvs=[]; colors=[]
            for point,normal,uv,color in vertices:
                positions.extend(point); normals.extend(normal); uvs.extend(uv); colors.extend(color)
            count=len(vertices); index_data=struct.pack('<%dI'%count,*range(count))
            interleaved=bytearray()
            for i in range(count):
                interleaved.extend(struct.pack('<12f',*(positions[i*3:i*3+3]+normals[i*3:i*3+3]+uvs[i*2:i*2+2]+colors[i*4:i*4+4])))
            view=add_bytes(interleaved,34962,48)
            pos_accessor=len(accessors); accessors.append({"bufferView":view,"byteOffset":0,"componentType":5126,"count":count,"type":"VEC3","min":[min(positions[i::3]) for i in range(3)],"max":[max(positions[i::3]) for i in range(3)]})
            norm_accessor=len(accessors); accessors.append({"bufferView":view,"byteOffset":12,"componentType":5126,"count":count,"type":"VEC3"})
            uv_accessor=len(accessors); accessors.append({"bufferView":view,"byteOffset":24,"componentType":5126,"count":count,"type":"VEC2"})
            color_accessor=len(accessors); accessors.append({"bufferView":view,"byteOffset":32,"componentType":5126,"count":count,"type":"VEC4"})
            index_view=add_bytes(index_data,34963); index_accessor=len(accessors)
            accessors.append({"bufferView":index_view,"componentType":5125,"count":count,"type":"SCALAR","min":[0],"max":[count-1]})
            primitives.append({"attributes":{"POSITION":pos_accessor,"NORMAL":norm_accessor,"TEXCOORD_0":uv_accessor,"COLOR_0":color_accessor},"indices":index_accessor,"material":mat_id,"mode":4})
    if primitives:
        meshes.append({"name":"DendroForestStage","primitives":primitives}); nodes.append({"name":"DendroForestStage","mesh":0})
    while len(blob)%4: blob.append(0)
    doc={"asset":{"version":"2.0","generator":"Genshin Archive Dendro Stage"},"scene":0,"scenes":[{"nodes":list(range(len(nodes)))}],"nodes":nodes,"meshes":meshes,"materials":materials,"textures":textures,"images":images,"samplers":[{"magFilter":9729,"minFilter":9987,"wrapS":10497,"wrapT":10497}],"buffers":[{"byteLength":len(blob)}],"bufferViews":views,"accessors":accessors}
    encoded=json.dumps(doc,separators=(',',':')).encode('utf-8')
    while len(encoded)%4: encoded+=b' '
    total=12+8+len(encoded)+8+len(blob)
    with open(OUTPUT,'wb') as out:
        out.write(struct.pack('<4sII',b'glTF',2,total)); out.write(struct.pack('<II',len(encoded),0x4E4F534A)); out.write(encoded)
        out.write(struct.pack('<II',len(blob),0x004E4942)); out.write(blob)
    for filepath in temporary_textures:
        try: os.remove(filepath)
        except OSError: pass
    return len(nodes)

export_status=None
try:
    export_status=export_glb_without_depsgraph()
finally:
    if window:
        window.scene=original_scene
    # The existing character scene, collections, objects, and materials were never edited.
    for obj in list(scene.objects):
        bpy.data.objects.remove(obj,do_unlink=True)
    if not isolated_scene:
        bpy.data.scenes.remove(scene)
    for datablocks in (bpy.data.meshes,bpy.data.curves,bpy.data.materials,bpy.data.worlds):
        for block in list(datablocks):
            if block.name.startswith(PREFIX) and block.users==0:
                datablocks.remove(block)

result={"status":"exported","path":OUTPUT,"bytes":os.path.getsize(OUTPUT),"objects":len(created),"export_status":str(export_status)}
