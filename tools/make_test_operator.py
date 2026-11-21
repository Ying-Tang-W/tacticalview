#!/usr/bin/env python3
"""生成测试用 GLB：低模干员（Mixamo 骨骼命名，22 根骨骼，5 种材质，一段 Walk 循环动画）。

用法： python3 tools/make_test_operator.py <输出 .glb 路径>

这是占位资产，只用来验证 加载 -> 动画 -> 蒙皮 -> 渲染 这条链路；之后换成真实模型即可。
坐标：Y 向上，角色面朝 +Z，角色的左侧是 +X，脚底在 y=0，身高约 1.8。
"""
import json
import math
import struct
import sys

# ---------------------------------------------------------------- 骨骼
# 名称 -> (父骨骼, 休息姿态下的世界坐标)
BONES = [
    ("Hips", None, (0, 0.95, 0)),
    ("Spine", "Hips", (0, 1.05, 0)),
    ("Spine1", "Spine", (0, 1.20, 0)),
    ("Spine2", "Spine1", (0, 1.35, 0)),
    ("Neck", "Spine2", (0, 1.50, 0)),
    ("Head", "Neck", (0, 1.60, 0)),
    ("LeftShoulder", "Spine2", (0.10, 1.45, 0)),
    ("LeftArm", "LeftShoulder", (0.20, 1.45, 0)),
    ("LeftForeArm", "LeftArm", (0.20, 1.15, 0)),
    ("LeftHand", "LeftForeArm", (0.20, 0.88, 0)),
    ("RightShoulder", "Spine2", (-0.10, 1.45, 0)),
    ("RightArm", "RightShoulder", (-0.20, 1.45, 0)),
    ("RightForeArm", "RightArm", (-0.20, 1.15, 0)),
    ("RightHand", "RightForeArm", (-0.20, 0.88, 0)),
    ("LeftUpLeg", "Hips", (0.10, 0.95, 0)),
    ("LeftLeg", "LeftUpLeg", (0.10, 0.50, 0)),
    ("LeftFoot", "LeftLeg", (0.10, 0.08, 0)),
    ("LeftToeBase", "LeftFoot", (0.10, 0.03, 0.15)),
    ("RightUpLeg", "Hips", (-0.10, 0.95, 0)),
    ("RightLeg", "RightUpLeg", (-0.10, 0.50, 0)),
    ("RightFoot", "RightLeg", (-0.10, 0.08, 0)),
    ("RightToeBase", "RightFoot", (-0.10, 0.03, 0.15)),
]
BONE_INDEX = {name: i for i, (name, _, _) in enumerate(BONES)}

# ---------------------------------------------------------------- 材质 (name, rgba)
MATERIALS = [
    ("skin", (0.85, 0.68, 0.55, 1.0)),
    ("uniform", (0.30, 0.36, 0.22, 1.0)),
    ("vest", (0.14, 0.16, 0.14, 1.0)),
    ("helmet", (0.22, 0.26, 0.18, 1.0)),
    ("boots", (0.10, 0.10, 0.10, 1.0)),
]
MAT = {name: i for i, (name, _) in enumerate(MATERIALS)}

# ---------------------------------------------------------------- 部件：(骨骼, 材质, 中心, 尺寸)
PARTS = [
    ("Head", "skin", (0, 1.68, 0), (0.22, 0.24, 0.24)),
    ("Head", "helmet", (0, 1.76, 0), (0.27, 0.15, 0.29)),
    ("Spine2", "uniform", (0, 1.30, 0), (0.34, 0.30, 0.20)),
    ("Spine2", "vest", (0, 1.30, 0.115), (0.30, 0.26, 0.05)),
    ("Spine", "uniform", (0, 1.07, 0), (0.30, 0.20, 0.18)),
    ("Hips", "uniform", (0, 0.93, 0), (0.32, 0.16, 0.20)),
]
for side, sx in (("Left", 1), ("Right", -1)):
    PARTS += [
        (side + "Arm", "uniform", (0.20 * sx, 1.30, 0), (0.10, 0.30, 0.10)),
        (side + "ForeArm", "uniform", (0.20 * sx, 1.02, 0), (0.09, 0.28, 0.09)),
        (side + "Hand", "skin", (0.20 * sx, 0.84, 0), (0.08, 0.08, 0.08)),
        (side + "UpLeg", "uniform", (0.10 * sx, 0.72, 0), (0.14, 0.44, 0.14)),
        (side + "Leg", "uniform", (0.10 * sx, 0.29, 0), (0.12, 0.42, 0.12)),
        (side + "Foot", "boots", (0.10 * sx, 0.05, 0.04), (0.12, 0.08, 0.26)),
    ]

# 立方体 6 个面：(外法线, 4 个顶点的符号 (sx, sy, sz))，顶点顺序从外向内看为逆时针
FACES = [
    ((0, 0, 1), [(-1, -1, 1), (1, -1, 1), (1, 1, 1), (-1, 1, 1)]),
    ((0, 0, -1), [(1, -1, -1), (-1, -1, -1), (-1, 1, -1), (1, 1, -1)]),
    ((-1, 0, 0), [(-1, -1, -1), (-1, -1, 1), (-1, 1, 1), (-1, 1, -1)]),
    ((1, 0, 0), [(1, -1, 1), (1, -1, -1), (1, 1, -1), (1, 1, 1)]),
    ((0, 1, 0), [(-1, 1, 1), (1, 1, 1), (1, 1, -1), (-1, 1, -1)]),
    ((0, -1, 0), [(-1, -1, -1), (1, -1, -1), (1, -1, 1), (-1, -1, 1)]),
]


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def build_primitives():
    """按材质分组，返回 {材质下标: dict(positions, normals, joints, weights, indices)}。"""
    groups = {}
    for bone, mat, c, s in PARTS:
        g = groups.setdefault(MAT[mat], {"pos": [], "nrm": [], "jnt": [], "wgt": [], "idx": []})
        for normal, corners in FACES:
            base = len(g["pos"])
            pts = []
            for sx, sy, sz in corners:
                p = (c[0] + sx * s[0] / 2, c[1] + sy * s[1] / 2, c[2] + sz * s[2] / 2)
                pts.append(p)
                g["pos"].append(p)
                g["nrm"].append(normal)
                g["jnt"].append((BONE_INDEX[bone], 0, 0, 0))
                g["wgt"].append((1.0, 0.0, 0.0, 0.0))
            # 自检：叉积方向必须与外法线一致（逆时针 = 正面）
            n = cross(sub(pts[1], pts[0]), sub(pts[2], pts[0]))
            assert dot(n, normal) > 0, "face winding is inward for part %s" % bone
            g["idx"] += [base, base + 1, base + 2, base, base + 2, base + 3]
    return groups


def quat_x(angle):
    return (math.sin(angle / 2), 0.0, 0.0, math.cos(angle / 2))


def build_walk():
    """1 秒的走路循环。向前摆腿/臂 = 绕 X 轴负角度；膝盖向后弯 = 正角度。"""
    times = [i / 8 for i in range(9)]
    swing = math.radians(30)
    knee = math.radians(30)

    def rot(fn):
        return [quat_x(fn(t)) for t in times]

    tau = 2 * math.pi
    channels = {
        "LeftUpLeg": rot(lambda t: -swing * math.sin(tau * t)),
        "RightUpLeg": rot(lambda t: swing * math.sin(tau * t)),
        "LeftLeg": rot(lambda t: knee * max(0.0, math.cos(tau * t))),
        "RightLeg": rot(lambda t: knee * max(0.0, -math.cos(tau * t))),
        "LeftArm": rot(lambda t: swing * 0.8 * math.sin(tau * t)),
        "RightArm": rot(lambda t: -swing * 0.8 * math.sin(tau * t)),
        "LeftForeArm": rot(lambda t: -math.radians(20)),
        "RightForeArm": rot(lambda t: -math.radians(20)),
    }
    hips_y = [0.95 + 0.02 * math.cos(2 * tau * t) for t in times]
    return times, channels, hips_y


class Bin:
    def __init__(self):
        self.data = bytearray()
        self.views = []
        self.accessors = []

    def add(self, payload, ctype, count, typ, target=None, minmax=None):
        while len(self.data) % 4:
            self.data.append(0)
        offset = len(self.data)
        self.data += payload
        view = {"buffer": 0, "byteOffset": offset, "byteLength": len(payload)}
        if target:
            view["target"] = target
        self.views.append(view)
        acc = {"bufferView": len(self.views) - 1, "componentType": ctype, "count": count, "type": typ}
        if minmax:
            acc["min"], acc["max"] = minmax
        self.accessors.append(acc)
        return len(self.accessors) - 1


def mat4_translation(x, y, z):
    # glTF 列主序
    return [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1]


def main(out_path):
    groups = build_primitives()
    b = Bin()

    primitives = []
    for mat_idx in sorted(groups):
        g = groups[mat_idx]
        n = len(g["pos"])
        xs, ys, zs = zip(*g["pos"])
        pos = b.add(struct.pack("<%df" % (n * 3), *[v for p in g["pos"] for v in p]), 5126, n, "VEC3", 34962,
                    ([min(xs), min(ys), min(zs)], [max(xs), max(ys), max(zs)]))
        nrm = b.add(struct.pack("<%df" % (n * 3), *[v for p in g["nrm"] for v in p]), 5126, n, "VEC3", 34962)
        jnt = b.add(struct.pack("<%dB" % (n * 4), *[v for p in g["jnt"] for v in p]), 5121, n, "VEC4", 34962)
        wgt = b.add(struct.pack("<%df" % (n * 4), *[v for p in g["wgt"] for v in p]), 5126, n, "VEC4", 34962)
        idx = b.add(struct.pack("<%dH" % len(g["idx"]), *g["idx"]), 5123, len(g["idx"]), "SCALAR", 34963)
        primitives.append({
            "attributes": {"POSITION": pos, "NORMAL": nrm, "JOINTS_0": jnt, "WEIGHTS_0": wgt},
            "indices": idx, "material": mat_idx,
        })

    ibm = []
    for _, _, (x, y, z) in BONES:
        ibm += mat4_translation(-x, -y, -z)
    ibm_acc = b.add(struct.pack("<%df" % len(ibm), *ibm), 5126, len(BONES), "MAT4")

    times, channels, hips_y = build_walk()
    time_acc = b.add(struct.pack("<%df" % len(times), *times), 5126, len(times), "SCALAR",
                     minmax=([min(times)], [max(times)]))
    samplers, anim_channels = [], []
    # 节点 0 是网格节点，骨骼节点从 1 开始
    for bone, quats in channels.items():
        out = b.add(struct.pack("<%df" % (len(quats) * 4), *[v for q in quats for v in q]), 5126, len(quats), "VEC4")
        samplers.append({"input": time_acc, "output": out, "interpolation": "LINEAR"})
        anim_channels.append({"sampler": len(samplers) - 1,
                              "target": {"node": 1 + BONE_INDEX[bone], "path": "rotation"}})
    hy = b.add(struct.pack("<%df" % (len(hips_y) * 3), *[v for y in hips_y for v in (0.0, y, 0.0)]),
               5126, len(hips_y), "VEC3")
    samplers.append({"input": time_acc, "output": hy, "interpolation": "LINEAR"})
    anim_channels.append({"sampler": len(samplers) - 1, "target": {"node": 1 + BONE_INDEX["Hips"], "path": "translation"}})

    nodes = [{"name": "Operator", "mesh": 0, "skin": 0}]
    children = {i: [] for i in range(len(BONES))}
    for i, (_, parent, _) in enumerate(BONES):
        if parent is not None:
            children[BONE_INDEX[parent]].append(1 + i)
    for i, (name, parent, pos) in enumerate(BONES):
        pp = BONES[BONE_INDEX[parent]][2] if parent else (0, 0, 0)
        node = {"name": "mixamorig:" + name,
                "translation": [pos[0] - pp[0], pos[1] - pp[1], pos[2] - pp[2]]}
        if children[i]:
            node["children"] = children[i]
        nodes.append(node)

    gltf = {
        "asset": {"version": "2.0", "generator": "tacticalview make_test_operator.py"},
        "scene": 0,
        "scenes": [{"nodes": [0, 1]}],
        "nodes": nodes,
        "meshes": [{"name": "Operator", "primitives": primitives}],
        "materials": [{"name": name, "pbrMetallicRoughness": {
            "baseColorFactor": list(rgba), "metallicFactor": 0.0, "roughnessFactor": 0.9}}
            for name, rgba in MATERIALS],
        "skins": [{"name": "OperatorSkin", "inverseBindMatrices": ibm_acc,
                   "joints": [1 + i for i in range(len(BONES))], "skeleton": 1}],
        "animations": [{"name": "Walk", "samplers": samplers, "channels": anim_channels}],
        "accessors": b.accessors,
        "bufferViews": b.views,
        "buffers": [{"byteLength": 0}],
    }
    while len(b.data) % 4:
        b.data.append(0)
    gltf["buffers"][0]["byteLength"] = len(b.data)

    js = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    js += b" " * ((4 - len(js) % 4) % 4)
    total = 12 + 8 + len(js) + 8 + len(b.data)
    with open(out_path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total))
        f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
        f.write(struct.pack("<II", len(b.data), 0x004E4942) + bytes(b.data))
    tri = sum(len(g["idx"]) // 3 for g in groups.values())
    print("wrote %s: %d bytes, %d triangles, %d joints, %d materials" % (out_path, total, tri, len(BONES), len(MATERIALS)))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: make_test_operator.py <out.glb>")
    main(sys.argv[1])
