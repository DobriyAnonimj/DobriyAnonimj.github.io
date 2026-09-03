"""
Pack Animation exporter for Blender
Author: Dobriy_Anonimj

Exports the active Action on the selected Armature into the JSON keyframe
animation format used by the "Pack Animation" Minecraft mod (built on top of
KosmX's Player Animator library). See docs/PACK_FORMAT.md in the mod project
for the full format description, and blender_addon/README.md in this folder
for install + usage instructions.
"""

import json
import re
import math

import bpy
from bpy_extras.io_utils import ExportHelper
from bpy.props import BoolProperty, StringProperty, FloatProperty
from bpy.types import Operator

bl_info = {
    "name": "Pack Animation (.json) exporter",
    "author": "Dobriy_Anonimj",
    "version": (1, 0, 0),
    "blender": (3, 6, 0),
    "location": "File > Export > Pack Animation (.json)",
    "description": "Export an armature action to the Pack Animation mod's keyframe json format",
    "category": "Import-Export",
}

# Maps a normalized bone name (lowercase, no separators) to the canonical
# Pack Animation bone name. Add more aliases here if your rig uses different
# naming - just make sure the normalized form (lowercase, "_"/"-" removed)
# ends up on the right side of one of these entries.
BONE_NAME_MAP = {
    "head": "head",

    "torso": "torso",
    "chest": "torso",
    "upperbody": "torso",

    "rightarm": "right_arm",
    "armright": "right_arm",
    "rarm": "right_arm",

    "leftarm": "left_arm",
    "armleft": "left_arm",
    "larm": "left_arm",

    "rightleg": "right_leg",
    "legright": "right_leg",
    "rleg": "right_leg",

    "leftleg": "left_leg",
    "legleft": "left_leg",
    "lleg": "left_leg",

    "body": "body",
    "root": "body",
    "wholebody": "body",
}


def normalize(name):
    return name.lower().replace("_", "").replace("-", "").replace(" ", "")


def canonical_bone_name(pose_bone_name):
    return BONE_NAME_MAP.get(normalize(pose_bone_name))


class PACKANIMATION_OT_export(Operator, ExportHelper):
    """Export the active armature's current action as a Pack Animation json"""

    bl_idname = "export_scene.packanimation_json"
    bl_label = "Export Pack Animation JSON"
    bl_options = {"PRESET"}

    filename_ext = ".json"
    filter_glob: StringProperty(default="*.json", options={"HIDDEN"})

    animation_name: StringProperty(
        name="Animation name",
        description=(
            "IMPORTANT: this is the key the animation is registered under, and it "
            "MUST be exactly 'idle', 'walk' or 'run' (lowercase) for the Pack "
            "Animation mod to find it. Player Animator indexes animations by this "
            "name, NOT by the file name"
        ),
        default="idle",
    )
    loop: BoolProperty(
        name="Loop",
        description="Mark the animation as looping (should be ON for idle/walk/run)",
        default=True,
    )
    swap_yz: BoolProperty(
        name="Swap Y/Z rotation axis",
        description=(
            "Rig-orientation toggle. Minecraft bone rotation order is [x, y, z] "
            "in degrees. Depending on how your armature was built/imported, "
            "Blender's local Y and Z rotation might need to be swapped to line "
            "up with Minecraft's axes. If your test animation twists the wrong "
            "way in game, toggle this and re-export"
        ),
        default=False,
    )
    position_scale: FloatProperty(
        name="Position scale",
        description="Multiplier applied to bone location values (16 Minecraft "
                     "model units = 1 block = Blender's default unit in most MC rigs)",
        default=16.0,
    )
    skip_static_bones: BoolProperty(
        name="Skip unanimated bones",
        description="Do not write bones that never move during the exported range",
        default=True,
    )

    def execute(self, context):
        obj = context.object
        if obj is None or obj.type != "ARMATURE":
            self.report({"ERROR"}, "Select an Armature object with an active action first")
            return {"CANCELLED"}
        if obj.animation_data is None or obj.animation_data.action is None:
            self.report({"ERROR"}, "The armature has no active action to export")
            return {"CANCELLED"}

        scene = context.scene
        fps = scene.render.fps / scene.render.fps_base
        frame_start = scene.frame_start
        frame_end = scene.frame_end
        original_frame = scene.frame_current

        # bone canonical name -> {"rotation": {time_str: [x,y,z]}, "position": {time_str: [x,y,z]}}
        tracks = {}
        pose_bones = {}
        for pbone in obj.pose.bones:
            canonical = canonical_bone_name(pbone.name)
            if canonical is not None:
                pose_bones[canonical] = pbone
                tracks[canonical] = {"rotation": {}, "position": {}}

        if not pose_bones:
            self.report({"ERROR"},
                        "No recognized bone names found. Bones must be named "
                        "head / torso / right_arm / left_arm / right_leg / left_leg / body "
                        "(camelCase also works). See docs/PACK_FORMAT.md")
            return {"CANCELLED"}

        try:
            for frame in range(frame_start, frame_end + 1):
                scene.frame_set(frame)
                time_key = self._format_time((frame - frame_start) / fps)

                for canonical, pbone in pose_bones.items():
                    rot = self._bone_rotation_degrees(pbone)
                    tracks[canonical]["rotation"][time_key] = rot

                    if canonical == "body":
                        loc = pbone.location
                        tracks[canonical]["position"][time_key] = [
                            round(loc.x * self.position_scale, 4),
                            round(loc.z * self.position_scale, 4),
                            round(loc.y * self.position_scale, 4),
                        ]
        finally:
            scene.frame_set(original_frame)

        bones_json = {}
        for canonical, track in tracks.items():
            rotation = track["rotation"]
            position = track["position"]

            if self.skip_static_bones and self._all_same(rotation) and self._all_same(position):
                continue

            entry = {}
            if rotation and not (self.skip_static_bones and self._all_zero(rotation)):
                entry["rotation"] = rotation
            if position and not (self.skip_static_bones and self._all_zero(position)):
                entry["position"] = position
            if entry:
                bones_json[canonical] = entry

        if not bones_json:
            self.report({"WARNING"}, "Nothing to export: no bone moved during the selected frame range")

        # Player Animator регистрирует анимацию под ЭТИМ именем (namespace берётся
        # из папки пака), поэтому ключ пишется как есть, без префиксов. Допустимы
        # только символы, разрешённые в Identifier: a-z, 0-9, _, -, ., /
        animation_key = re.sub(r"[^a-z0-9_./-]", "_", (self.animation_name or "idle").strip().lower())
        if not animation_key:
            animation_key = "idle"
        data = {
            "format_version": "1.8.0",
            "animations": {
                animation_key: {
                    "loop": bool(self.loop),
                    "animation_length": round((frame_end - frame_start) / fps, 4),
                    "bones": bones_json,
                }
            },
        }

        with open(self.filepath, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
            f.write("\n")

        self.report({"INFO"}, "Pack Animation: exported '{}' ({} bones, {:.2f}s)".format(
            self.filepath, len(bones_json), data["animations"][animation_key]["animation_length"]))
        return {"FINISHED"}

    def _bone_rotation_degrees(self, pbone):
        # Bake through a quaternion so this works regardless of the pose
        # bone's rotation_mode (Euler XYZ, Quaternion, Axis-Angle, ...).
        quat = pbone.rotation_quaternion.copy() if pbone.rotation_mode == "QUATERNION" \
            else pbone.matrix_basis.to_quaternion()
        euler = quat.to_euler("XYZ")
        x = math.degrees(euler.x)
        y = math.degrees(euler.z if self.swap_yz else euler.y)
        z = math.degrees(euler.y if self.swap_yz else euler.z)
        return [round(x, 3), round(y, 3), round(z, 3)]

    @staticmethod
    def _format_time(t):
        # Trim trailing zeros but always keep at least one decimal, e.g. "1.0", "0.35".
        s = "{:.4f}".format(t).rstrip("0")
        if s.endswith("."):
            s += "0"
        return s

    @staticmethod
    def _all_zero(track):
        return all(all(abs(v) < 1e-4 for v in vec) for vec in track.values())

    @staticmethod
    def _all_same(track):
        values = list(track.values())
        if not values:
            return True
        first = values[0]
        return all(all(abs(a - b) < 1e-4 for a, b in zip(v, first)) for v in values)


def menu_func_export(self, context):
    self.layout.operator(PACKANIMATION_OT_export.bl_idname, text="Pack Animation (.json)")


def register():
    bpy.utils.register_class(PACKANIMATION_OT_export)
    bpy.types.TOPBAR_MT_file_export.append(menu_func_export)


def unregister():
    bpy.types.TOPBAR_MT_file_export.remove(menu_func_export)
    bpy.utils.unregister_class(PACKANIMATION_OT_export)


if __name__ == "__main__":
    register()
