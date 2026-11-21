package tacticalview.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import tacticalview.core.anim.Animator;
import tacticalview.core.anim.Pose;
import tacticalview.core.anim.Skinner;
import tacticalview.core.config.ModelDefinition;
import tacticalview.core.event.EventBus;
import tacticalview.core.event.Events;
import tacticalview.core.gltf.GltfLoader;
import tacticalview.core.gltf.GltfModel;
import tacticalview.core.json.Json;
import tacticalview.core.lod.LodSelector;
import tacticalview.core.math.Mat4;
import tacticalview.core.retarget.BoneMap;
import tacticalview.core.retarget.HumanoidPose;
import tacticalview.core.retarget.Retargeter;
import tacticalview.core.retarget.Slot;
import tacticalview.core.socket.Socket;
import tacticalview.core.socket.SocketSet;
import tacticalview.core.validate.AssetValidator;
import tacticalview.core.validate.ImageInfo;
import tacticalview.core.validate.ModelStats;

/** 零依赖自测：java -cp out tacticalview.core.SelfTest。任何断言失败都会以非零状态退出。 */
public final class SelfTest {
    private static int checks = 0;

    public static void main(String[] args) {
        testJson();
        testMatrix();
        GltfModel model = testLoad();
        testSkinning(model);
        testBlend(model);
        testSockets(model);
        testRetarget(model);
        testLod();
        testValidator(model);
        testDefinition();
        testEventBus();
        System.out.println("ALL OK (" + checks + " checks)");
    }

    // ---------------------------------------------------------------- 工具

    private static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            throw new AssertionError("FAILED: " + msg);
        }
    }

    private static void near(float actual, float expected, String msg) {
        check(Math.abs(actual - expected) < 1e-4f, msg + " expected " + expected + " got " + actual);
    }

    private static void near3(float[] a, int o, float x, float y, float z, String msg) {
        near(a[o], x, msg + ".x");
        near(a[o + 1], y, msg + ".y");
        near(a[o + 2], z, msg + ".z");
    }

    // ---------------------------------------------------------------- 合成 GLB

    /** 两个三角形组成的竖条：下半段绑 Hips，上半段绑 Spine；Spine 在 1 秒内绕 Z 转 90°。 */
    private static byte[] buildGlb() {
        ByteBuffer bin = ByteBuffer.allocate(308).order(ByteOrder.LITTLE_ENDIAN);
        float[] pos = {0, 0, 0, 1, 0, 0, 0, 2, 0, 1, 2, 0};
        for (float f : pos) bin.putFloat(f);                       // 0..47
        byte[] joints = {0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0};
        bin.put(joints);                                           // 48..63
        for (int i = 0; i < 4; i++) {                              // 64..127
            bin.putFloat(1f).putFloat(0f).putFloat(0f).putFloat(0f);
        }
        short[] idx = {0, 1, 2, 2, 1, 3};
        for (short s : idx) bin.putShort(s);                       // 128..139
        float[] ibm = new Mat4().m.clone();                        // 140..267：关节 0 单位阵
        for (float f : ibm) bin.putFloat(f);
        float[] ibm1 = Mat4.translation(0, -1, 0).m;               // 关节 1：Spine 平移 (0,1,0) 的逆
        for (float f : ibm1) bin.putFloat(f);
        bin.putFloat(0f).putFloat(1f);                             // 268..275 时间
        float h = (float) Math.sin(Math.PI / 4), c = (float) Math.cos(Math.PI / 4);
        bin.putFloat(0).putFloat(0).putFloat(0).putFloat(1);       // 276..307 旋转 key
        bin.putFloat(0).putFloat(0).putFloat(h).putFloat(c);

        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0,1]}],"
                + "\"nodes\":[{\"name\":\"Body\",\"mesh\":0,\"skin\":0},"
                + "{\"name\":\"mixamorig:Hips\",\"children\":[2]},"
                + "{\"name\":\"mixamorig:Spine\",\"translation\":[0,1,0]}],"
                + "\"meshes\":[{\"name\":\"Body\",\"primitives\":[{\"attributes\":{\"POSITION\":0,\"JOINTS_0\":1,\"WEIGHTS_0\":2},\"indices\":3,\"material\":0}]}],"
                + "\"materials\":[{\"name\":\"m\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[1,0,0,1],\"metallicFactor\":0.5,\"roughnessFactor\":0.25}}],"
                + "\"skins\":[{\"joints\":[1,2],\"inverseBindMatrices\":4}],"
                + "\"animations\":[{\"name\":\"bend\",\"channels\":[{\"sampler\":0,\"target\":{\"node\":2,\"path\":\"rotation\"}}],"
                + "\"samplers\":[{\"input\":5,\"output\":6,\"interpolation\":\"LINEAR\"}]}],"
                + "\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":4,\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5121,\"count\":4,\"type\":\"VEC4\"},"
                + "{\"bufferView\":2,\"componentType\":5126,\"count\":4,\"type\":\"VEC4\"},"
                + "{\"bufferView\":3,\"componentType\":5123,\"count\":6,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":4,\"componentType\":5126,\"count\":2,\"type\":\"MAT4\"},"
                + "{\"bufferView\":5,\"componentType\":5126,\"count\":2,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":6,\"componentType\":5126,\"count\":2,\"type\":\"VEC4\"}],"
                + "\"bufferViews\":["
                + "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":48},"
                + "{\"buffer\":0,\"byteOffset\":48,\"byteLength\":16},"
                + "{\"buffer\":0,\"byteOffset\":64,\"byteLength\":64},"
                + "{\"buffer\":0,\"byteOffset\":128,\"byteLength\":12},"
                + "{\"buffer\":0,\"byteOffset\":140,\"byteLength\":128},"
                + "{\"buffer\":0,\"byteOffset\":268,\"byteLength\":8},"
                + "{\"buffer\":0,\"byteOffset\":276,\"byteLength\":32}],"
                + "\"buffers\":[{\"byteLength\":308}]}";
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int jsonPad = (4 - jsonBytes.length % 4) % 4;
        int jsonLen = jsonBytes.length + jsonPad;
        int total = 12 + 8 + jsonLen + 8 + 308;
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67).putInt(2).putInt(total);
        out.putInt(jsonLen).putInt(0x4E4F534A).put(jsonBytes);
        for (int i = 0; i < jsonPad; i++) out.put((byte) ' ');
        out.putInt(308).putInt(0x004E4942).put(bin.array());
        return out.array();
    }

    // ---------------------------------------------------------------- 测试

    private static void testJson() {
        Object o = Json.parse("{\"a\":[1,2.5,-3e2],\"b\":{\"c\":\"x\\u00e9\\n\"},\"d\":true,\"e\":null}");
        var m = Json.obj(o);
        check(Json.arr(m.get("a")).size() == 3, "json array size");
        near(((Number) Json.arr(m.get("a")).get(2)).floatValue(), -300f, "json exponent");
        check("xé\n".equals(Json.str(Json.obj(m.get("b")), "c", null)), "json string escapes");
        check(Json.bool(m, "d", false), "json bool");
        boolean threw = false;
        try {
            Json.parse("{\"a\":}");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "json rejects malformed input");
    }

    private static void testMatrix() {
        float[] t = {1, 2, 3}, s = {2, 3, 4};
        float[] q = tacticalview.core.math.Quat.fromAxisAngle(0.3f, 0.5f, 0.8f, 1.1f);
        Mat4 m = Mat4.fromTRS(t, 0, q, 0, s, 0);
        float[] t2 = new float[3], q2 = new float[4], s2 = new float[3];
        m.decompose(t2, q2, s2);
        near3(t2, 0, 1, 2, 3, "decompose t");
        near3(s2, 0, 2, 3, 4, "decompose s");
        float dot = q[0] * q2[0] + q[1] * q2[1] + q[2] * q2[2] + q[3] * q2[3];
        near(Math.abs(dot), 1f, "decompose q");
        float[] p = new float[3];
        m.mul(m.inverseAffine()).transformPoint(5, -6, 7, p, 0);
        near3(p, 0, 5, -6, 7, "M * M^-1 is identity");
    }

    private static GltfModel testLoad() {
        GltfModel model = GltfLoader.loadGlb(buildGlb());
        check(model.nodes.size() == 3, "node count");
        check(model.nodes.get(2).parent == 1, "parent link");
        check(model.rootNodes.equals(List.of(0, 1)), "scene roots");
        GltfModel.Primitive p = model.meshes.get(0).primitives.get(0);
        check(p.vertexCount() == 4 && p.triangleCount() == 2, "primitive counts");
        check(p.joints[8] == 1 && p.joints[0] == 0, "joint indices");
        near(p.weights[0], 1f, "weights");
        check(model.skins.get(0).joints.length == 2, "skin joints");
        near(model.skins.get(0).inverseBind[1][13], -1f, "inverse bind translation");
        near(model.materials.get(0).roughness, 0.25f, "material roughness");
        near(model.materials.get(0).baseColor[0], 1f, "material base color");
        near(model.animations.get(0).duration, 1f, "animation duration");
        ModelStats st = ModelStats.of(model);
        check(st.triangles() == 2 && st.vertices() == 4 && st.joints() == 2, "stats " + st);
        boolean threw = false;
        try {
            GltfLoader.loadGlb(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "rejects non-GLB");
        return model;
    }

    private static float[] skinAt(GltfModel model, float time) {
        Animator an = new Animator(model);
        Pose pose = an.restPose();
        an.sample(model.animations.get(0), time, false, pose);
        Mat4[] world = an.computeWorld(pose);
        Mat4[] jm = an.jointMatrices(model.skins.get(0), 0, world);
        GltfModel.Primitive p = model.meshes.get(0).primitives.get(0);
        float[] out = new float[p.positions.length];
        Skinner.skinPositions(p, jm, out);
        return out;
    }

    private static void testSkinning(GltfModel model) {
        float[] r0 = skinAt(model, 0f);
        near3(r0, 0, 0, 0, 0, "t=0 v0");
        near3(r0, 9, 1, 2, 0, "t=0 v3 unchanged at rest");
        float[] r1 = skinAt(model, 1f);
        near3(r1, 0, 0, 0, 0, "t=1 v0 (Hips-bound, fixed)");
        near3(r1, 6, -1, 1, 0, "t=1 v2 rotated 90 deg about spine");
        near3(r1, 9, -1, 2, 0, "t=1 v3 rotated 90 deg about spine");
        float[] rh = skinAt(model, 0.5f);
        near3(rh, 9, 0, 2.4142137f, 0, "t=0.5 v3 (45 deg slerp)");
        float[] rc = skinAt(model, 7.0f);   // 不循环时夹在末尾
        near3(rc, 9, -1, 2, 0, "clamp past end");
        Animator an = new Animator(model);
        Pose pose = an.restPose();
        an.sample(model.animations.get(0), 1.5f, true, pose);   // 循环：1.5 -> 0.5
        Mat4[] w = an.computeWorld(pose);
        float[] tp = new float[3];
        w[2].transformPoint(1, 1, 0, tp, 0);
        near3(tp, 0, 0, 2.4142137f, 0, "loop wraps time");
    }

    private static void testBlend(GltfModel model) {
        Animator an = new Animator(model);
        Pose a = an.restPose(), b = an.restPose(), half = an.restPose();
        an.sample(model.animations.get(0), 0f, false, a);
        an.sample(model.animations.get(0), 1f, false, b);
        an.sample(model.animations.get(0), 0.5f, false, half);
        Pose mix = an.restPose();
        Animator.blend(a, b, 0.5f, mix);
        for (int i = 0; i < 4; i++) {
            near(mix.r[8 + i], half.r[8 + i], "blend matches mid sample q[" + i + "]");
        }
    }

    private static void testSockets(GltfModel model) {
        SocketSet set = new SocketSet();
        set.add(new Socket("muzzle", "Spine", new float[] {0, 1, 0}, new float[] {0, 0, 0, 1}, new float[] {1, 1, 1}, "muzzle_flash"));
        set.add(new Socket("ghost", "NoSuchBone", new float[3], new float[] {0, 0, 0, 1}, new float[] {1, 1, 1}, null));
        List<String> unresolved = set.bind(model);
        check(unresolved.equals(List.of("ghost")), "unresolved sockets " + unresolved);
        Animator an = new Animator(model);
        Pose pose = an.restPose();
        an.sample(model.animations.get(0), 1f, false, pose);
        Mat4[] world = an.computeWorld(pose);
        float[] p = new float[3];
        set.worldTransform("muzzle", world).transformPoint(0, 0, 0, p, 0);
        near3(p, 0, -1, 1, 0, "socket follows bone");
        boolean threw = false;
        try {
            set.worldTransform("ghost", world);
        } catch (IllegalStateException e) {
            threw = true;
        }
        check(threw, "unbound socket throws");
    }

    private static void testRetarget(GltfModel model) {
        BoneMap bm = BoneMap.fromModel(model);
        check(bm.get(Slot.HIPS) == 1 && bm.get(Slot.SPINE) == 2, "mixamo bone map");
        check(bm.get(Slot.HEAD) == -1, "missing bone reports -1");
        Animator an = new Animator(model);
        Pose pose = an.restPose();
        HumanoidPose hp = new HumanoidPose();
        hp.bodyYaw = (float) (Math.PI / 2);
        hp.headYaw = 0.5f;   // 没有 Head 骨骼，不应计数
        int applied = Retargeter.apply(an, model, pose, bm, hp);
        check(applied == 1, "retarget applied count " + applied);
        near(pose.r[1 * 4 + 1], (float) Math.sin(Math.PI / 4), "hips yaw quaternion y");
        near(pose.r[1 * 4 + 3], (float) Math.cos(Math.PI / 4), "hips yaw quaternion w");

        // 父骨骼已转 90° 时，子骨骼的角色空间增量应被换算到局部轴
        Pose p2 = an.restPose();
        HumanoidPose hp2 = new HumanoidPose();
        hp2.bodyYaw = (float) (Math.PI / 2);
        hp2.crouch = 1f;   // Spine 绕角色空间 +X 前倾
        bm = BoneMap.fromModel(model);
        Retargeter.apply(an, model, p2, bm, hp2);
        check(p2.r[2 * 4] != 0f || p2.r[2 * 4 + 1] != 0f || p2.r[2 * 4 + 2] != 0f, "spine rotated by crouch");
    }

    private static void testLod() {
        LodSelector lod = new LodSelector(new float[] {10f, 30f}, 0.1f);
        check(lod.levelCount() == 3, "lod levels");
        check(lod.select(5f, -1) == 0, "lod near");
        check(lod.select(10.5f, 0) == 0, "lod hysteresis holds finer");
        check(lod.select(11.5f, 0) == 1, "lod switches coarser");
        check(lod.select(9.5f, 1) == 1, "lod hysteresis holds coarser");
        check(lod.select(8.5f, 1) == 0, "lod switches finer");
        check(lod.select(100f, -1) == 2, "lod far");
        boolean threw = false;
        try {
            new LodSelector(new float[] {10f, 10f}, 0f);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "lod rejects non-increasing distances");
    }

    private static void testValidator(GltfModel model) {
        var strict = AssetValidator.validate(model, 1);
        check(strict.stream().anyMatch(i -> i.severity() == AssetValidator.Severity.ERROR
                && i.message().contains("triangle")), "triangle budget error");
        var lenient = AssetValidator.validate(model, AssetValidator.DEFAULT_TRIANGLE_BUDGET);
        check(lenient.stream().noneMatch(i -> i.severity() == AssetValidator.Severity.ERROR), "no errors within budget");
        check(lenient.stream().anyMatch(i -> i.message().contains("lacks textures")), "reports missing PBR textures");
        check(lenient.stream().anyMatch(i -> i.message().contains("humanoid bones")), "reports missing humanoid bones");

        byte[] png = new byte[33];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, png, 0, 8);
        png[18] = 0x08;   // width 2048 = 0x0800
        png[19] = 0x00;
        png[22] = 0x08;   // height 2048
        png[23] = 0x00;
        int[] wh = ImageInfo.dimensions(png);
        check(wh != null && wh[0] == 2048 && wh[1] == 2048, "png dimensions");
        check(ImageInfo.dimensions(new byte[] {1, 2, 3}) == null, "unknown image format -> null");
    }

    private static void testDefinition() {
        String json = "{\"id\":\"operator_a\",\"scale\":1.05,\"offset\":[0,0.1,0],"
                + "\"lods\":[{\"model\":\"lod0.glb\",\"maxDistance\":8},{\"model\":\"lod1.glb\",\"maxDistance\":24},{\"model\":\"lod2.glb\"}],"
                + "\"sockets\":[{\"name\":\"muzzle\",\"bone\":\"RightHand\",\"position\":[0,0.1,0.6],\"effect\":\"muzzle_flash\"}],"
                + "\"animations\":{\"reload\":\"Rifle_Reload\"},"
                + "\"firstPerson\":{\"offset\":[0.2,-0.3,0.4],\"scale\":0.9}}";
        ModelDefinition d = ModelDefinition.parse(json);
        check(d.id.equals("operator_a"), "definition id");
        check(d.lods.size() == 3 && Float.isInfinite(d.lods.get(2).maxDistance), "definition lods");
        check(d.lodSelector(0.1f).levelCount() == 3, "definition lod selector");
        check(d.sockets.get("muzzle").effect.equals("muzzle_flash"), "definition socket effect");
        near(d.sockets.get("muzzle").position[2], 0.6f, "definition socket position");
        check(d.animations.get("reload").equals("Rifle_Reload"), "definition animation map");
        near(d.firstPerson.offset[0], 0.2f, "definition first person");
        boolean threw = false;
        try {
            ModelDefinition.parse("{\"id\":\"x\",\"lods\":[{\"model\":\"a.glb\"},{\"model\":\"b.glb\"}]}");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "definition rejects missing maxDistance on non-last LOD");
        threw = false;
        try {
            ModelDefinition.parse("{\"scale\":1}");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "definition requires id");
    }

    private static void testEventBus() {
        EventBus bus = new EventBus();
        int[] seen = {0};
        String[] effect = {null};
        Runnable unsubscribe = bus.register(Events.SocketEffect.class, e -> {
            seen[0]++;
            effect[0] = e.effect();
        });
        bus.register(Events.PreRender.class, e -> seen[0] += 100);
        int n = bus.post(new Events.SocketEffect("m", 1, "muzzle", "muzzle_flash", new Mat4()));
        check(n == 1 && seen[0] == 1 && "muzzle_flash".equals(effect[0]), "event dispatch by type");
        unsubscribe.run();
        check(bus.post(new Events.SocketEffect("m", 1, "muzzle", "x", new Mat4())) == 0, "unsubscribe works");
    }
}
